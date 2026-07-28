package ai.moeru.airicraft.agent.events;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticEventBufferTest {
	@Test
	void querySinceFiltersAndMarksTruncationAfterRollover() {
		AtomicLong now = new AtomicLong(1_000L);
		SemanticEventBuffer buffer = new SemanticEventBuffer(3, now::getAndIncrement);
		buffer.append(1L, "a", Map.of("value", 1));
		buffer.append(2L, "b", Map.of("value", 2));
		buffer.append(3L, "c", Map.of("value", 3));
		buffer.append(4L, "d", Map.of("value", 4));

		SemanticEventQueryResult result = buffer.query(0L);
		assertEquals(2L, result.oldestSeqNo());
		assertEquals(4L, result.latestSeqNo());
		assertTrue(result.truncated());
		assertEquals(3, result.events().size());
		assertEquals("b", result.events().get(0).type());
		assertEquals("d", result.events().get(2).type());
		assertFalse(buffer.query(1L).truncated(), "a cursor immediately before the oldest event has no gap");
	}

	@Test
	void queryWithoutSinceReturnsCurrentBuffer() {
		AtomicLong now = new AtomicLong(2_000L);
		SemanticEventBuffer buffer = new SemanticEventBuffer(3, now::getAndIncrement);
		buffer.append(10L, "session.world_loaded", Map.of());

		SemanticEventQueryResult result = buffer.query(null);
		assertEquals(1, result.events().size());
		assertEquals("session.world_loaded", result.events().get(0).type());
		assertEquals(2_000L, result.events().get(0).timestampMs());
		assertTrue(!result.truncated());
	}

	@Test
	void containsAndCountsSinceFilterByTypeAndPlayer() {
		AtomicLong now = new AtomicLong(3_000L);
		SemanticEventBuffer buffer = new SemanticEventBuffer(8, now::getAndIncrement);
		buffer.append(1L, "social.player_spoke", Map.of("player", "Alice"));
		buffer.append(2L, "social.player_spoke", Map.of("player", "Bob"));
		buffer.append(3L, "social.player_spoke", Map.of("player", "Alice"));
		buffer.append(4L, "planner.goal_set", Map.of("player", "Alice"));

		assertEquals(4L, buffer.latestSeqNo());
		assertTrue(buffer.containsTypeSince(1L, "social.player_spoke"));
		assertEquals(2, buffer.countTypeSince(1L, "social.player_spoke"));
		assertTrue(buffer.containsTypeForPlayerSince(1L, "social.player_spoke", "Alice"));
		assertEquals(1, buffer.countTypeForPlayerSince(1L, "social.player_spoke", "Alice"));
	}

	@Test
	void shutdownClearCanPreserveSequenceForLateTerminalEvidence() {
		SemanticEventBuffer buffer = new SemanticEventBuffer(8, () -> 4_000L);
		buffer.append(1L, "runtime.shutdown_started", Map.of());
		long recorderCursor = buffer.latestSeqNo();

		buffer.clearPreservingSequence();
		assertEquals(recorderCursor, buffer.latestSeqNo());
		assertFalse(buffer.query(recorderCursor).truncated());
		buffer.append(2L, "runtime.shutdown_finished", Map.of());

		SemanticEventQueryResult result = buffer.query(recorderCursor);
		assertEquals(2L, result.latestSeqNo());
		assertEquals(1, result.events().size());
		assertEquals("runtime.shutdown_finished", result.events().getFirst().type());
	}
}
