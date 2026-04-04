package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
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

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		LlmConversation first = firstSnapshot.plannerConversation();
		assertEquals(6, first.messages().size());
		assertEquals(LlmMessageKind.NOTICE, first.messages().get(1).kind());
		assertTrue(first.messages().get(1).content().contains("local time"));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("Session mode is currently out of world.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("There is no primary interaction player right now.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("There is no active goal right now.")));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);

		PlannerContextSnapshot secondSnapshot = freezeSnapshot(aggregator, requestAt(10 * 60_000L, "Alice", "@agent follow me"));
		LlmConversation second = secondSnapshot.plannerConversation();
		long noticeCount = second.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(0L, noticeCount);
		aggregator.commitAcceptedTriggerBatch(secondSnapshot);

		PlannerContextSnapshot thirdSnapshot = freezeSnapshot(aggregator, requestAt(31 * 60_000L, "Alice", "@agent stop"));
		LlmConversation third = thirdSnapshot.plannerConversation();
		long updatedNoticeCount = third.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(1L, updatedNoticeCount);
	}

	@Test
	void recordsAmbientContextAndSemanticEventsAsFrozenNotices() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "follow.target_acquired", Map.of("player", "Alice")),
			new SemanticEvent(2L, 101L, 9_000L, "planner.goal_set", Map.of("goalType", "FOLLOW_PLAYER", "targetPlayer", "Alice"))
		));

		PlannerContextSnapshot snapshot = freezeSnapshot(aggregator, new PlannerRequest(
			200L,
			10_000L,
			SessionMode.REMOTE_MULTIPLAYER,
			"Alice",
			new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 200L, "planner"),
			"Bob",
			"status?",
			null
		));
		LlmConversation conversation = snapshot.plannerConversation();

		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Primary interaction player is Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Active goal: Follow Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Started following Alice")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("The planner set goal FOLLOW_PLAYER for Alice")));
	}

	@Test
	void recordsProjectedMixedEventBatchAsCoalescedNoticesInFirstSeenOrder() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1)),
			new SemanticEvent(2L, 101L, 8_100L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1)),
			new SemanticEvent(3L, 102L, 8_200L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 2)),
			new SemanticEvent(4L, 103L, 8_300L, "follow.target_acquired", Map.of("player", "Alice"))
		));

		LlmConversation conversation = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi")).plannerConversation();
		List<LlmChatMessage> notices = conversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.NOTICE)
			.toList();

		assertTrue(notices.stream().anyMatch(message -> message.content().contains("3x minecraft:dirt")));
		assertTrue(notices.stream().anyMatch(message -> message.content().contains("1x minecraft:cobblestone")));
		assertTrue(notices.stream().anyMatch(message -> message.content().contains("Started following Alice")));

		int dirtIndex = indexContaining(notices, "3x minecraft:dirt");
		int cobbleIndex = indexContaining(notices, "1x minecraft:cobblestone");
		int followIndex = indexContaining(notices, "Started following Alice");
		assertTrue(dirtIndex < cobbleIndex);
		assertTrue(cobbleIndex < followIndex);
	}

	@Test
	void coalescesMatchingSemanticUpdatesAcrossMultipleRecordBatchesBeforeFreeze() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(2L, 101L, 8_100L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(3L, 102L, 8_200L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:wheat_seeds", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(4L, 103L, 8_300L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));

		LlmConversation conversation = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi")).plannerConversation();
		List<LlmChatMessage> notices = conversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.NOTICE)
			.filter(message -> message.content().contains("picked up"))
			.toList();

		assertEquals(2, notices.size());
		assertTrue(notices.get(0).content().contains("3x minecraft:sunflower"));
		assertTrue(notices.get(1).content().contains("1x minecraft:wheat_seeds"));
	}

	@Test
	void compactionConversationAppendsTaskInstructionAtTail() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot initialSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.commitAcceptedTriggerBatch(initialSnapshot);
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
		LlmConversation afterCheckpoint = freezeSnapshot(aggregator, requestAt(32 * 60_000L, "Alice", "@agent status")).plannerConversation();
		assertEquals(LlmMessageKind.CHECKPOINT, afterCheckpoint.messages().get(1).kind());
	}

	@Test
	void acceptedAssistantHistoryIsRenderedWithoutFrozenRelativeTimeText() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);
		aggregator.recordAgentTurn(new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "On it.", 20L, 1_000L));

		clock.advanceMillis(120_000L);
		LlmConversation laterConversation = freezeSnapshot(aggregator, requestAt(clock.millis(), "Alice", "@agent status")).plannerConversation();

		assertTrue(laterConversation.messages().stream().anyMatch(message ->
			"assistant".equals(message.role()) && "On it.".equals(message.content())
		));
		assertFalse(laterConversation.messages().stream().anyMatch(message -> message.content().contains("Agent replied just now")));
	}

	private static PlannerRequest requestAt(long timestampMs, String sender, String message) {
		return new PlannerRequest(
			timestampMs / 50L,
			timestampMs,
			SessionMode.OUT_OF_WORLD,
			null,
			null,
			sender,
			message,
			null
		);
	}

	private static void recordEvents(PlannerContextAggregator aggregator, long anchorTimeMs, List<SemanticEvent> events) {
		aggregator.recordObservedEvents(
			new SemanticEventQueryResult(
				events.isEmpty() ? 0L : events.getFirst().seqNo(),
				events.isEmpty() ? 0L : events.getLast().seqNo(),
				false,
				events
			)
		);
	}

	private static PlannerContextSnapshot freezeSnapshot(PlannerContextAggregator aggregator, PlannerRequest request) {
		if (request.triggerBatch() != null) {
			for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
				aggregator.enqueueTrigger(trigger);
			}
		}
		return aggregator.freezePlannerSnapshot(request);
	}

	private static int indexContaining(List<LlmChatMessage> messages, String fragment) {
		for (int index = 0; index < messages.size(); index++) {
			if (messages.get(index).content().contains(fragment)) {
				return index;
			}
		}
		return -1;
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private final ZoneId zoneId;

		private MutableClock(Instant instant, ZoneId zoneId) {
			this.instant = instant;
			this.zoneId = zoneId;
		}

		@Override
		public ZoneId getZone() {
			return zoneId;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advanceMillis(long millis) {
			instant = instant.plusMillis(millis);
		}

		@Override
		public long millis() {
			return instant.toEpochMilli();
		}
	}
}
