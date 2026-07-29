package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalReflexRuntimeTest {
	@Test
	void drowningStartsAtLowAirThresholdOrFromDrowningDamage() {
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(true, 100, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(true, 101, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(false, 0, 100, false));
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(false, 300, 100, true));
	}

	@Test
	void drowningRequiresTwelveBreathableRecoveringTicksToResolve() {
		assertTrue(SurvivalReflexRuntime.breathableAndRecovering(false, 80, 300, 70));
		assertTrue(SurvivalReflexRuntime.breathableAndRecovering(false, 300, 300, 300));
		assertFalse(SurvivalReflexRuntime.breathableAndRecovering(false, 70, 300, 70));
		assertFalse(SurvivalReflexRuntime.breathableAndRecovering(true, 80, 300, 70));
		assertFalse(SurvivalReflexRuntime.drowningResolved(11));
		assertTrue(SurvivalReflexRuntime.drowningResolved(12));
	}

	@Test
	void idleDrowningRequiresSafeLandButInterruptedWorkOnlyRequiresAir() {
		assertEquals(SurvivalReflexAction.REACH_SAFE_LAND, SurvivalReflexRuntime.drowningAction(false));
		assertEquals(SurvivalReflexAction.SWIM_TO_AIR, SurvivalReflexRuntime.drowningAction(true));
		assertFalse(SurvivalReflexRuntime.stableDrowningRecovery(false, true, false));
		assertTrue(SurvivalReflexRuntime.stableDrowningRecovery(false, true, true));
		assertTrue(SurvivalReflexRuntime.stableDrowningRecovery(true, true, false));
		assertFalse(SurvivalReflexRuntime.stableDrowningRecovery(true, false, true));
		assertTrue(SurvivalReflexRuntime.shouldNavigateToSafeLand(true, true));
		assertFalse(SurvivalReflexRuntime.shouldNavigateToSafeLand(false, true));
		assertFalse(SurvivalReflexRuntime.shouldNavigateToSafeLand(true, false));
	}

	@Test
	void defendRequiresHighHealthOneCloseVisibleThreat() {
		assertTrue(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.5D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.5D, 1, 4.0D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 2, 4.0D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.6D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.0D, false, 0.5D));
	}

	@Test
	void proactiveDetectionRequiresCloseVisibleLivingHostile() {
		assertTrue(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 8.0D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 8.01D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 4.0D, false));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(false, true, 4.0D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, false, 4.0D, true));
	}

	@Test
	void threatResolutionHonorsDamageCooldown() {
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(1, 200, 100, 60));
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(0, 159, 100, 60));
		assertTrue(SurvivalReflexRuntime.mobThreatsResolved(0, 160, 100, 60));
	}

	@Test
	void newDangerPreemptsSafetyHoldButDoesNotRestartActiveReflex() {
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.IDLE, true));
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.ACTIVE, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, false));
	}

	@Test
	void resolvedDrowningHoldTreadsWaterUntilPlannerDecision() {
		assertTrue(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, false));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.ACTIVE, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.MOB_ATTACK, true));
	}

	@Test
	void fleeRecoveryAlternatesStrafeAndBackstep() {
		SurvivalReflexRuntime.EscapeKeys normal = SurvivalReflexRuntime.escapeKeys(false, 0);
		SurvivalReflexRuntime.EscapeKeys left = SurvivalReflexRuntime.escapeKeys(true, 0);
		SurvivalReflexRuntime.EscapeKeys right = SurvivalReflexRuntime.escapeKeys(true, 20);
		SurvivalReflexRuntime.EscapeKeys backLeft = SurvivalReflexRuntime.escapeKeys(true, 40);

		assertTrue(normal.forward() && normal.sprint() && normal.jump());
		assertTrue(left.left());
		assertTrue(right.right());
		assertTrue(backLeft.back() && backLeft.left());
		assertNotEquals(left, right);
	}

	@Test
	void resumeRequiresResolvedMatchingSafetyHold() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertEquals(SurvivalReflexRuntime.ResumeResult.REFLEX_ACTIVE,
			SurvivalReflexRuntime.validateResume(active, "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.NO_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(SurvivalReflexSnapshot.idle(), "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.STALE_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(awaiting, "old-hold"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.RESUMED,
			SurvivalReflexRuntime.validateResume(awaiting, "hold-1"));
	}

	@Test
	void activeAndDrowningHoldOwnActuationWhileBothHoldNormalWork() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertTrue(active.ownsActuation());
		assertTrue(active.holdsNormalTasks());
		assertTrue(awaiting.ownsActuation());
		assertTrue(awaiting.holdsNormalTasks());
	}

	private static SurvivalReflexSnapshot snapshot(SurvivalReflexState state, String holdId) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 2L, holdId,
			"job-1", "action-1", List.of(), 10.0F, 20.0F, 100, 300, 10L, 20L, 0, null
		);
	}
}
