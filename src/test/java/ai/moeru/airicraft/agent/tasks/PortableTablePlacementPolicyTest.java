package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableTablePlacementPolicyTest {
	@Test
	void candidatePositionsIncludePlayerCellForTunnelFallback() {
		GoalPosition origin = position(2, 59, -5);

		assertTrue(PortableTablePlacementPolicy.candidatePositions(origin).contains(origin));
		assertTrue(PortableTablePlacementPolicy.candidatePositions(origin).contains(position(3, 61, -5)));
	}

	@Test
	void adjacentSiteIsPreferredButPlayerSpaceSurvivesAttemptLimit() {
		GoalPosition origin = position(0, 64, 0);
		List<PortableTablePlacementPolicy.SiteObservation> observations = new ArrayList<>();
		for (GoalPosition candidate : PortableTablePlacementPolicy.candidatePositions(origin)) {
			observations.add(observation(candidate, true, candidate.equals(origin)));
		}

		List<GoalPosition> ranked = PortableTablePlacementPolicy.rankFeasibleSites(origin, observations, 3);

		assertEquals(3, ranked.size());
		assertFalse(ranked.getFirst().equals(origin));
		assertEquals(origin, ranked.getLast());
	}

	@Test
	void rankingUsesStaticFeasibilityWithoutReachOrLineOfSight() {
		GoalPosition origin = position(0, 64, 0);
		GoalPosition feasible = position(1, 64, 0);
		GoalPosition blockedTarget = position(0, 64, 1);
		GoalPosition missingSupport = position(-1, 64, 0);

		List<GoalPosition> ranked = PortableTablePlacementPolicy.rankFeasibleSites(
			origin,
			List.of(
				observation(feasible, true, false),
				new PortableTablePlacementPolicy.SiteObservation(blockedTarget, true, false, true, false),
				new PortableTablePlacementPolicy.SiteObservation(missingSupport, true, true, false, false)
			),
			PortableTablePlacementPolicy.MAX_ATTEMPTS
		);

		assertEquals(List.of(feasible), ranked);
	}

	@Test
	void attemptsAdvanceDeterministicallyAndTimeoutIsBounded() {
		GoalPosition first = position(1, 64, 0);
		GoalPosition second = position(0, 64, 1);
		PortableTablePlacementPolicy.AttemptState state = new PortableTablePlacementPolicy.AttemptState(
			List.of(first, second),
			100L
		);

		assertEquals(first, state.activeTarget());
		assertFalse(state.timedOut(100L + PortableTablePlacementPolicy.TIMEOUT_TICKS));

		state = state.advance("safe_stand_position_not_found");
		assertEquals(second, state.activeTarget());
		assertEquals("safe_stand_position_not_found", state.lastFailure());

		state = state.advance("target_not_visible");
		assertTrue(state.exhausted());
		assertTrue(state.timedOut(101L + PortableTablePlacementPolicy.TIMEOUT_TICKS));
	}

	@Test
	void invariantFailuresTerminateInsteadOfBurningEveryCandidate() {
		assertEquals(
			PortableTablePlacementPolicy.FailureDisposition.TERMINATE,
			PortableTablePlacementPolicy.failureDisposition("required_item_missing itemId=minecraft:crafting_table")
		);
		assertEquals(
			PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE,
			PortableTablePlacementPolicy.failureDisposition("safe_stand_position_not_found")
		);
	}

	private static PortableTablePlacementPolicy.SiteObservation observation(
		GoalPosition target,
		boolean feasible,
		boolean playerOccupied
	) {
		return new PortableTablePlacementPolicy.SiteObservation(
			target,
			true,
			feasible,
			feasible,
			playerOccupied
		);
	}

	private static GoalPosition position(int x, int y, int z) {
		return new GoalPosition(x, y, z, true);
	}
}
