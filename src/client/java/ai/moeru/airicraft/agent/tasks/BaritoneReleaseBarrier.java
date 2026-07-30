package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;

/**
 * Shared ownership boundary for transitions between Baritone users.
 * Internal cancellation events are drained by the facade and a new owner may
 * start only after both process ownership and cancellation provenance clear.
 */
final class BaritoneReleaseBarrier {
	private BaritoneReleaseBarrier() {
	}

	static boolean releaseAndDrain(BaritoneFacade baritone) {
		if (baritone == null || !baritone.isLoaded()) {
			return true;
		}
		if (baritone.processActive() && !baritone.cancellationPending()) {
			baritone.cancel();
		}
		return released(baritone);
	}

	static boolean released(BaritoneFacade baritone) {
		return baritone == null
			|| !baritone.isLoaded()
			|| (!baritone.processActive() && !baritone.cancellationPending());
	}
}
