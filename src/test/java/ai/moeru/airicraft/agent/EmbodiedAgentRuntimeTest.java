package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbodiedAgentRuntimeTest {
	@Test
	void suppressesRecentEchoOfAgentOwnPublicChat() {
		assertTrue(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void doesNotSuppressDifferentOrStaleChat() {
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"follow me",
			"Player918",
			"hello there",
			120L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			200L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"magpie",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void detectsLocalControllerMessagesByMatchingClientPlayerName() {
		assertTrue(EmbodiedAgentRuntime.isLocalControllerMessage("Player918", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage("magpie", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage(null, "Player918"));
	}

	@Test
	void taskExecutorPausesWhenSessionDoesNotAllowActuation() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.SINGLEPLAYER_LOCAL,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.onClientTick(null);

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, runtime.taskExecutionSnapshot().state());
	}

	@Test
	void terminalTaskEventCreatesSemanticEventAndDialogueTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"test"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			goal,
			TaskExecutionState.COMPLETED,
			"Goal reached"
		));

		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(goal);

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker()) && turn.text().contains("TASK UPDATE: state=COMPLETED")
		));
	}

	@Test
	void submitTaskPlannerResponseThreadsTaskSnapshotIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"On it.",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals(TaskType.COLLECT_RESOURCE, runtime.snapshot().task().spec().type());
		assertTrue(runtime.snapshot().taskExecution().state() == TaskExecutionState.IDLE
			|| runtime.snapshot().taskExecution().state() == TaskExecutionState.RUNNING);
	}

	@Test
	void missionUpdatePlannerResponseThreadsLedgerIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					2,
					"Collect logs"
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						new FinishStepArgs("Mission complete")
					),
					List.of("collect_logs"),
					LedgerStepStatus.PENDING,
					List.of(new EvidenceRequirement(EvidenceKind.STEP_COMPLETED, null, null, "collect_logs", null)),
					0,
					"Finish"
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Starting the mission.",
			new DialogueIntent(
				DialogueIntentType.MISSION_UPDATE,
				null,
				null,
				null,
				null,
				null,
				ledger
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals("mission-wood-1", runtime.snapshot().task().mission().missionId());
		assertEquals("collect_logs", runtime.snapshot().task().activeStepId());
		assertEquals(ledger, runtime.snapshot().task().ledger());
	}

	@Test
	void directGoalSubmissionCancelsActiveTaskFirst() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(1, 64, 1, true),
				null,
				null
			),
			30L
		));

		assertEquals(TaskState.CANCELLED, runtime.snapshot().task().state());
		assertEquals(GoalType.NAVIGATE_TO, runtime.activeGoal().orElseThrow().type());
	}

	@Test
	void submitTaskClearsPreviouslyActiveDirectGoal() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertTrue(runtime.activeGoal().isEmpty());
		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
	}

	@Test
	void semanticTaskFailureEmitsTaskEventAndInternalUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 4),
			21L,
			"task_runtime"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			goal,
			TaskExecutionState.FAILED,
			"Path calculation failed"
		));

		for (int tick = 0; tick < 24; tick++) {
			runtime.onClientTick(null);
		}

		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker()) && turn.text().contains("TASK UPDATE: state=FAILED")
		));
	}

	@Test
	void directGoalEventsAreNotSuppressedAfterTaskHasAlreadyTerminated() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), "bridge_debug");
		runtime.cancelTask("user_cancelled");
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			30L,
			"test"
		));

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.started".equals(event.type())));
	}

	private static final class FakeWorldTaskExecutor implements WorldTaskExecutor {
		private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
		private Optional<TaskTerminalEvent> nextTerminalEvent = Optional.empty();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<GoalSnapshot> activeGoal) {
			if (!sessionSnapshot.companionActuationAllowed()) {
				snapshot = new TaskExecutionSnapshot(
					TaskExecutionState.PAUSED_BY_SESSION_GATE,
					activeGoal.orElse(null),
					null,
					null,
					null
				);
			}
			else if (nextTerminalEvent.isPresent()) {
				TaskTerminalEvent terminalEvent = nextTerminalEvent.get();
				snapshot = new TaskExecutionSnapshot(
					terminalEvent.terminalState(),
					terminalEvent.goal(),
					null,
					terminalEvent.terminalState().name(),
					null
				);
			}
			else {
				snapshot = new TaskExecutionSnapshot(
					activeGoal.isPresent() ? TaskExecutionState.RUNNING : TaskExecutionState.IDLE,
					activeGoal.orElse(null),
					null,
					null,
					null
				);
			}

			Optional<TaskTerminalEvent> result = nextTerminalEvent;
			nextTerminalEvent = Optional.empty();
			return result;
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return snapshot;
		}

		@Override
		public void onWorldLeave() {
			snapshot = TaskExecutionSnapshot.idle();
		}

		@Override
		public void shutdown() {
			snapshot = TaskExecutionSnapshot.idle();
		}
	}
}
