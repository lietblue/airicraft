package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerContextAggregatorTest {
	@Test
	void injectsSingleTimeBeaconPerThirtyMinuteWindow() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		LlmConversation first = aggregator.buildPlannerConversation(requestAt(1_000L, "Alice", "@agent hi"));
		assertEquals(6, first.messages().size());
		assertEquals(LlmMessageKind.NOTICE, first.messages().get(1).kind());
		assertTrue(first.messages().get(1).content().contains("local time"));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("Session mode is currently out of world.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("There is no primary interaction player right now.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("There is no active goal right now.")));

		LlmConversation second = aggregator.buildPlannerConversation(requestAt(10 * 60_000L, "Alice", "@agent follow me"));
		long noticeCount = second.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(4L, noticeCount);

		LlmConversation third = aggregator.buildPlannerConversation(requestAt(31 * 60_000L, "Alice", "@agent stop"));
		long updatedNoticeCount = third.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(5L, updatedNoticeCount);
	}

	@Test
	void recordsAmbientContextAndSemanticEventsAsFrozenNotices() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		aggregator.recordEvents(List.of(
			new SemanticEvent(1L, 100L, 8_000L, "follow.target_acquired", Map.of("player", "Alice")),
			new SemanticEvent(2L, 101L, 9_000L, "planner.goal_set", Map.of("goalType", "FOLLOW_PLAYER", "targetPlayer", "Alice"))
		), 10_000L);

		LlmConversation conversation = aggregator.buildPlannerConversation(new PlannerRequest(
			200L,
			10_000L,
			SessionMode.REMOTE_MULTIPLAYER,
			"Alice",
			new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 200L, "planner"),
			null,
			null,
			"Bob",
			"status?",
			null
		));

		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Primary interaction player is Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Active goal: Follow Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Started following Alice")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("The planner set goal FOLLOW_PLAYER for Alice")));
	}

	@Test
	void includesMissionAndEvidenceNoticesWhenPresent() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(new LedgerStep(
				"collect_logs",
				LedgerStepKind.COLLECT_RESOURCE,
				new LedgerStepPayload(
					new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
					null, null, null, null, null, null, null, null, null, null, null
				),
				List.of(),
				LedgerStepStatus.ACTIVE,
				List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
				1,
				"Collect logs"
			)),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);

		LlmConversation conversation = aggregator.buildPlannerConversation(new PlannerRequest(
			200L,
			10_000L,
			SessionMode.REMOTE_MULTIPLAYER,
			"Alice",
			null,
			new TaskSnapshot(
				TaskState.RUNNING,
				new MissionSpec("mission-wood-1", MissionType.COLLECT_RESOURCE, "Collect 4 wood logs"),
				ledger,
				null,
				new TaskProgressSnapshot(2, 2),
				TaskStep.MINE_TARGET,
				TaskOwnership.TASK_RUNTIME,
				"planner_response",
				null,
				"collect_logs",
				LedgerStepKind.COLLECT_RESOURCE,
				StepExecutionResult.idle(),
				200L
			),
			new MissionExecutionSnapshot(
				new MissionSpec("mission-wood-1", MissionType.COLLECT_RESOURCE, "Collect 4 wood logs"),
				ledger,
				null,
				new WorldEvidence(Map.of(ai.moeru.airicraft.agent.tasks.TaskResourceKind.WOOD_LOGS, 2), Map.of("minecraft:oak_log", 3), "minecraft:overworld", 0, 64, 0, null, 200L),
				new StepExecutionResult("collect_logs", StepExecutionStatus.RUNNING, null, Map.of(), Map.of(), 200L),
				TaskExecutionSnapshot.idle()
			),
			"Bob",
			"status?",
			null
		));

		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Active mission: Mission COLLECT_RESOURCE")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Mission evidence snapshot:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Mission ledger snapshot:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Last step result:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Mission history summary:")));
	}

	@Test
	void compactionConversationAppendsTaskInstructionAtTail() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		aggregator.buildPlannerConversation(requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.recordUsage(new LlmUsageSnapshot(70_000, 200, 70_200));

		assertTrue(aggregator.compactionPending());
		LlmConversation compactionConversation = aggregator.buildCompactionConversation();
		LlmChatMessage lastMessage = compactionConversation.messages().get(compactionConversation.messages().size() - 1);
		assertEquals(LlmMessageKind.TASK, lastMessage.kind());
		assertTrue(lastMessage.content().startsWith("COMPACTION TASK:"));

		aggregator.applyCheckpoint(new CompactionCheckpoint(
			"Tuesday afternoon",
			"in world",
			"follow Alice",
			java.util.List.of("follow Alice"),
			java.util.List.of("Alice is nearby"),
			java.util.List.of("Alice"),
			java.util.List.of("keep following"),
			java.util.List.of("Alice asked for follow"),
			java.util.List.of()
		));

		assertFalse(aggregator.compactionPending());
		LlmConversation afterCheckpoint = aggregator.buildPlannerConversation(requestAt(32 * 60_000L, "Alice", "@agent status"));
		assertEquals(LlmMessageKind.CHECKPOINT, afterCheckpoint.messages().get(1).kind());
	}

	private static PlannerRequest requestAt(long timestampMs, String sender, String message) {
		return new PlannerRequest(
			timestampMs / 50L,
			timestampMs,
			SessionMode.OUT_OF_WORLD,
			null,
			null,
			null,
			null,
			sender,
			message,
			null
		);
	}
}
