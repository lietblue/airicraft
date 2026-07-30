package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveHybridMiningEnvironmentTest {
	@Test
	void releaseWaitsForMineProcessEvenAfterCancellationWasDrained() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.mineProcessActive = true;
		LiveHybridMiningEnvironment environment = new LiveHybridMiningEnvironment(() -> null, facade);
		environment.beginRelease();
		facade.cancellationPending = false;

		HybridMiningPolicy.ReleaseStatus active = environment.observeRelease();

		assertFalse(active.ownershipReleased());
		assertTrue(active.cancellationDrained());
		facade.mineProcessActive = false;
		HybridMiningPolicy.ReleaseStatus released = environment.observeRelease();
		assertTrue(released.ownershipReleased());
		assertTrue(released.cancellationDrained());
	}

	@Test
	void inactiveMineProcessDoesNotExpectACancellationEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		LiveHybridMiningEnvironment environment = new LiveHybridMiningEnvironment(() -> null, facade);
		environment.beginRelease();

		HybridMiningPolicy.ReleaseStatus released = environment.observeRelease();

		assertTrue(released.ownershipReleased());
		assertTrue(released.cancellationDrained());
	}

	@Test
	void delayedExpectedCancellationMustActuallyBeObserved() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.mineProcessActive = true;
		facade.cancellationPending = true;
		LiveHybridMiningEnvironment environment = new LiveHybridMiningEnvironment(() -> null, facade);
		environment.beginRelease();

		HybridMiningPolicy.ReleaseStatus first = environment.observeRelease();
		HybridMiningPolicy.ReleaseStatus second = environment.observeRelease();
		facade.mineProcessActive = false;
		HybridMiningPolicy.ReleaseStatus releasedWithoutEvent = environment.observeRelease();
		facade.cancellationPending = false;
		HybridMiningPolicy.ReleaseStatus drained = environment.observeRelease();

		assertFalse(first.ownershipReleased());
		assertFalse(first.cancellationDrained());
		assertFalse(second.ownershipReleased());
		assertFalse(second.cancellationDrained());
		assertTrue(releasedWithoutEvent.ownershipReleased());
		assertFalse(releasedWithoutEvent.cancellationDrained());
		assertTrue(drained.cancellationDrained());
	}

	private static final class FakeBaritoneFacade implements BaritoneFacade {
		private boolean mineProcessActive;
		private boolean cancellationPending;

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void applySettings() {
		}

		@Override
		public double walkOnWaterPenalty() {
			return 3.0D;
		}

		@Override
		public void setWalkOnWaterPenalty(double value) {
		}

		@Override
		public void startFollow(String playerName) {
		}

		@Override
		public void startNavigate(GoalPosition position) {
		}

		@Override
		public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		}

		@Override
		public void startMine(GoalMineSpec spec) {
		}

		@Override
		public boolean mineProcessActive() {
			return mineProcessActive;
		}

		@Override
		public boolean processActive() {
			return mineProcessActive;
		}

		@Override
		public boolean cancel() {
			return false;
		}

		@Override
		public boolean cancellationPending() {
			return cancellationPending;
		}

		@Override
		public Optional<String> activeProcessName() {
			return Optional.empty();
		}

		@Override
		public Optional<Double> estimatedTicksToGoal() {
			return Optional.empty();
		}

		@Override
		public Optional<String> pollPathEvent() {
			return Optional.empty();
		}

		@Override
		public boolean navigationGoalReached(GoalPosition position) {
			return false;
		}
	}
}
