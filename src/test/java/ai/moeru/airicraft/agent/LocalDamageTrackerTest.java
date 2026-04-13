package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDamageTrackerTest {
	@Test
	void initialHealthSyncDoesNotCountAsDamage() {
		LocalDamageTracker tracker = new LocalDamageTracker();
		tracker.onLifecycleReset(10L);

		assertTrue(LocalDamageTracker.shouldCaptureHealthLoss(false, 20.0F, 18.0F));
		assertFalse(tracker.shouldCaptureHealthLossAfterLifecycle(false, 12L, 20.0F, 18.0F));
	}

	@Test
	void healingDoesNotEmitDamagePayload() {
		LocalDamageTracker tracker = new LocalDamageTracker();

		assertFalse(LocalDamageTracker.shouldCaptureHealthLoss(true, 8.0F, 10.0F));
		assertNull(tracker.consumeDamage(true, 10L, 8.0F, 10.0F));
	}

	@Test
	void damagePayloadIncludesExactHealthLossAndFatalFlag() {
		LocalDamageTracker tracker = new LocalDamageTracker();

		Map<String, Object> payload = tracker.consumeDamage(true, 20L, 7.5F, 0.0F);

		assertNotNull(payload);
		assertEquals("self", payload.get("actor"));
		assertEquals(7.5F, ((Number) payload.get("amount")).floatValue());
		assertEquals(7.5F, ((Number) payload.get("healthBefore")).floatValue());
		assertEquals(0.0F, ((Number) payload.get("healthAfter")).floatValue());
		assertEquals(Boolean.TRUE, payload.get("fatal"));
	}

	@Test
	void recentObservedDamageMetadataIsAttachedToHealthLoss() {
		LocalDamageTracker tracker = new LocalDamageTracker();
		tracker.observeDamage(30L, "minecraft:mob_attack", "Zombie", "minecraft:zombie", "minecraft:zombie");

		Map<String, Object> payload = tracker.consumeDamage(true, 31L, 20.0F, 17.5F);

		assertEquals("minecraft:mob_attack", payload.get("damageTypeId"));
		assertEquals("Zombie", payload.get("attackerName"));
		assertEquals("minecraft:zombie", payload.get("attackerEntityTypeId"));
		assertEquals("minecraft:zombie", payload.get("directSourceEntityTypeId"));
		assertNull(tracker.pendingObservation());
	}

	@Test
	void staleObservedDamageMetadataIsDroppedBeforeLaterHealthLoss() {
		LocalDamageTracker tracker = new LocalDamageTracker();
		tracker.observeDamage(40L, "minecraft:fall", null, null, null);
		tracker.pruneStale(43L);

		Map<String, Object> payload = tracker.consumeDamage(true, 43L, 10.0F, 8.0F);

		assertNotNull(payload);
		assertTrue(payload.containsKey("amount"));
		assertFalse(payload.containsKey("damageTypeId"));
		assertNull(tracker.pendingObservation());
	}

	@Test
	void uninitializedHealthStateDoesNotEmitDamagePayload() {
		LocalDamageTracker tracker = new LocalDamageTracker();
		tracker.onLifecycleReset(10L);

		assertNull(tracker.consumeDamage(false, 12L, 20.0F, 18.0F));
	}

	@Test
	void delayedUninitializedHealthLossAfterLifecycleResetEmitsDamagePayload() {
		LocalDamageTracker tracker = new LocalDamageTracker();
		tracker.onLifecycleReset(10L);

		Map<String, Object> payload = tracker.consumeDamage(false, 25L, 20.0F, 0.0F);

		assertNotNull(payload);
		assertEquals(Boolean.TRUE, payload.get("fatal"));
		assertEquals(20.0F, ((Number) payload.get("amount")).floatValue());
	}
}
