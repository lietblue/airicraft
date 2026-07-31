package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerItemDeliveryPolicyTest {
	private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final String ITEM = "minecraft:oak_log";

	@Test
	void approachesThenAimsBeforeDroppingForTheObservedTarget() {
		PlayerItemDeliveryPolicy.State state = PlayerItemDeliveryPolicy.initial(ITEM, 2);

		PlayerItemDeliveryPolicy.Decision approach = decide(state, target(5.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.APPROACH, approach.command());

		PlayerItemDeliveryPolicy.Decision aim = decide(approach.nextState(), target(2.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.AIM, aim.command());

		PlayerItemDeliveryPolicy.Decision drop = decide(aim.nextState(), target(2.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.DROP, drop.command());
	}

	@Test
	void succeedsOnlyAfterMatchingDroppedItemsReachTheTarget() {
		PlayerItemDeliveryPolicy.State state = droppedState();

		PlayerItemDeliveryPolicy.Decision waiting = decide(state, target(2.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.WAIT, waiting.command());

		PlayerItemDeliveryPolicy.Decision delivered = decide(waiting.nextState(), target(2.0D, 0.0D, 0.0D), List.of(
			new PlayerItemDeliveryPolicy.DroppedItemEvidence(10, ITEM, 2, 2.0D, 0.0D, 0.0D)
		));
		assertEquals(PlayerItemDeliveryPolicy.Command.SUCCEED, delivered.command());
	}

	@Test
	void keepsObservingAUserWhoMovesAfterTheDrop() {
		PlayerItemDeliveryPolicy.State state = droppedState();

		PlayerItemDeliveryPolicy.Decision approach = decide(state, target(6.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.APPROACH, approach.command());
		assertEquals("approach_moving_target", approach.reason());

		PlayerItemDeliveryPolicy.Decision waiting = decide(approach.nextState(), target(2.0D, 0.0D, 0.0D));
		assertEquals(PlayerItemDeliveryPolicy.Command.WAIT, waiting.command());
	}

	@Test
	void tracksPartialDeliveryAndFailsWhenTheTargetDisappears() {
		PlayerItemDeliveryPolicy.State state = droppedState();
		PlayerItemDeliveryPolicy.Decision partial = decide(state, target(2.0D, 0.0D, 0.0D), List.of(
			new PlayerItemDeliveryPolicy.DroppedItemEvidence(10, ITEM, 1, 2.0D, 0.0D, 0.0D)
		));

		PlayerItemDeliveryPolicy.Decision failed = decide(partial.nextState(), Optional.empty(), List.of());
		assertEquals(PlayerItemDeliveryPolicy.Command.FAIL, failed.command());
		assertEquals("partial_delivery delivered=1 requested=2", failed.reason());
	}

	@Test
	void failsWithoutDeliveryEvidenceAfterTheTimeout() {
		PlayerItemDeliveryPolicy.State state = droppedState();
		PlayerItemDeliveryPolicy.Decision decision = null;
		for (int tick = 0; tick <= PlayerItemDeliveryPolicy.DELIVERY_TIMEOUT_TICKS; tick++) {
			decision = decide(state, target(2.0D, 0.0D, 0.0D));
			state = decision.nextState();
			if (decision.command() == PlayerItemDeliveryPolicy.Command.FAIL) {
				break;
			}
		}

		assertEquals(PlayerItemDeliveryPolicy.Command.FAIL, decision.command());
		assertEquals("delivery_timeout", decision.reason());
	}

	@Test
	void rejectsAChangedTargetIdentity() {
		PlayerItemDeliveryPolicy.State state = droppedState();

		PlayerItemDeliveryPolicy.Decision decision = decide(state, new PlayerItemDeliveryPolicy.TargetObservation(
			OTHER,
			"Alice",
			2.0D,
			0.0D,
			0.0D
		));

		assertEquals(PlayerItemDeliveryPolicy.Command.FAIL, decision.command());
		assertEquals("target_identity_changed", decision.reason());
	}

	private static PlayerItemDeliveryPolicy.State droppedState() {
		PlayerItemDeliveryPolicy.State state = PlayerItemDeliveryPolicy.initial(ITEM, 2);
		PlayerItemDeliveryPolicy.Decision aim = decide(state, target(2.0D, 0.0D, 0.0D));
		PlayerItemDeliveryPolicy.Decision drop = decide(aim.nextState(), target(2.0D, 0.0D, 0.0D));
		return drop.nextState();
	}

	private static PlayerItemDeliveryPolicy.Decision decide(
		PlayerItemDeliveryPolicy.State state,
		PlayerItemDeliveryPolicy.TargetObservation target
	) {
		return decide(state, Optional.of(target), List.of());
	}

	private static PlayerItemDeliveryPolicy.Decision decide(
		PlayerItemDeliveryPolicy.State state,
		PlayerItemDeliveryPolicy.TargetObservation target,
		List<PlayerItemDeliveryPolicy.DroppedItemEvidence> evidence
	) {
		return decide(state, Optional.of(target), evidence);
	}

	private static PlayerItemDeliveryPolicy.Decision decide(
		PlayerItemDeliveryPolicy.State state,
		Optional<PlayerItemDeliveryPolicy.TargetObservation> target,
		List<PlayerItemDeliveryPolicy.DroppedItemEvidence> evidence
	) {
		return PlayerItemDeliveryPolicy.decide(state, new PlayerItemDeliveryPolicy.Observation(
			0.0D,
			0.0D,
			0.0D,
			2,
			target,
			evidence
		));
	}

	private static PlayerItemDeliveryPolicy.TargetObservation target(double x, double y, double z) {
		return new PlayerItemDeliveryPolicy.TargetObservation(ALICE, "Alice", x, y, z);
	}
}
