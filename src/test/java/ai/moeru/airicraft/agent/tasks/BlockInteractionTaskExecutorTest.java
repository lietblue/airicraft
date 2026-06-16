package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockInteractionTaskExecutorTest {
	@Test
	void directWaterPlacementRequiresAHorizontalCavity() {
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(0));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(1));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(2));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(3));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(4));
	}

	@Test
	void directWaterPlacementCanReplaceSimpleFarmTerrainTargets() {
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:air", true));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:short_grass", true));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:grass_block", false));
		assertTrue(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:dirt", false));
	}

	@Test
	void directWaterPlacementDoesNotReplaceArbitrarySolidTargets() {
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:stone", false));
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:oak_log", false));
		assertFalse(BlockInteractionTaskExecutor.isDirectWaterPlacementTarget("minecraft:chest", false));
	}

	@Test
	void useBlockInteractionModeChoosesFluidItemUseForFluidTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.FLUID_ITEM_USE,
			BlockInteractionTaskExecutor.useBlockInteractionMode(true, true)
		);
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.FLUID_ITEM_USE,
			BlockInteractionTaskExecutor.useBlockInteractionMode(true, false)
		);
	}

	@Test
	void useBlockInteractionModeChoosesSupportForNonFluidAirOrReplaceableTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.SUPPORT_INTERACTION,
			BlockInteractionTaskExecutor.useBlockInteractionMode(false, true)
		);
	}

	@Test
	void useBlockInteractionModeChoosesBlockClickForSolidNonFluidTargets() {
		assertEquals(
			BlockInteractionTaskExecutor.UseBlockInteractionMode.BLOCK_INTERACTION,
			BlockInteractionTaskExecutor.useBlockInteractionMode(false, false)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeWaitsWhilePathing() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.WAIT,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.empty(), 12)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeFailsOnPathFailureOrTimeout() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("CALC_FAILED"), 12)
		);
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("cancelled"), 12)
		);
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.FAILED,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.empty(), 161)
		);
	}

	@Test
	void blockInteractionNavigationOutcomeDistinguishesReachedButStillOutOfRange() {
		assertEquals(
			BlockInteractionTaskExecutor.BlockInteractionNavigationOutcome.AT_GOAL_BUT_STILL_OUT_OF_RANGE,
			BlockInteractionTaskExecutor.blockInteractionNavigationOutcome(Optional.of("AT_GOAL"), 12)
		);
	}

	@Test
	void directInteractionApproachHandlesNearbyOutOfReachTargets() {
		assertTrue(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(81.0D, false));
		assertTrue(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(100.0D, false));
	}

	@Test
	void directInteractionApproachDefersWhenFarOrStuck() {
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(100.1D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldUseDirectInteractionApproach(16.0D, true));
	}

	@Test
	void cancelledNearbyNavigationFallsBackToDirectApproach() {
		assertTrue(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 81.0D, false));
		assertTrue(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("cancelled"), 100.0D, false));
	}

	@Test
	void directApproachFallbackDefersForFarStuckOrNonCancelNavigationFailures() {
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 100.1D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CANCELED"), 16.0D, true));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.of("CALC_FAILED"), 16.0D, false));
		assertFalse(BlockInteractionTaskExecutor.shouldFallbackToDirectApproachAfterNavigationFailure(Optional.empty(), 16.0D, false));
	}

	@Test
	void interactionStandCandidatesPreferPositionsBesideTargetAndSupport() {
		assertEquals(
			List.of(
				new BlockPos(10, 65, 9),
				new BlockPos(10, 66, 9),
				new BlockPos(10, 65, 11),
				new BlockPos(10, 66, 11),
				new BlockPos(9, 65, 10),
				new BlockPos(9, 66, 10),
				new BlockPos(11, 65, 10),
				new BlockPos(11, 66, 10),
				new BlockPos(10, 64, 9),
				new BlockPos(10, 65, 9),
				new BlockPos(10, 64, 11),
				new BlockPos(10, 65, 11),
				new BlockPos(9, 64, 10),
				new BlockPos(9, 65, 10),
				new BlockPos(11, 64, 10),
				new BlockPos(11, 65, 10)
			),
			BlockInteractionTaskExecutor.interactionStandCandidates(
				new BlockPos(10, 65, 10),
				new BlockPos(10, 64, 10)
			)
		);
	}

	@Test
	void interactionBusyDispositionClosesEmptyOpenContainer() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.CLOSE_OPEN_SCREEN,
			BlockInteractionTaskExecutor.interactionBusyDisposition(true, true)
		);
	}

	@Test
	void interactionBusyDispositionFailsWhenCursorCarriesItem() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.FAIL,
			BlockInteractionTaskExecutor.interactionBusyDisposition(true, false)
		);
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.FAIL,
			BlockInteractionTaskExecutor.interactionBusyDisposition(false, false)
		);
	}

	@Test
	void interactionBusyDispositionAllowsNormalPlayerInventory() {
		assertEquals(
			BlockInteractionTaskExecutor.InteractionBusyDisposition.READY,
			BlockInteractionTaskExecutor.interactionBusyDisposition(false, true)
		);
	}

	@Test
	void supportRaycastOnlyRequiredForPlacementStyleInteractions() {
		assertFalse(BlockInteractionTaskExecutor.requiresSupportRaycast(false, true));
		assertTrue(BlockInteractionTaskExecutor.requiresSupportRaycast(true, false));
		assertTrue(BlockInteractionTaskExecutor.requiresSupportRaycast(false, false));
	}

	@Test
	void cropPlantingItemsDoNotRequireSupportRaycastAfterReachableNavigation() {
		assertTrue(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:wheat_seeds"));
		assertTrue(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:carrot"));
		assertFalse(BlockInteractionTaskExecutor.isCropPlantingItemId("minecraft:oak_planks"));
	}

	@Test
	void placementConfirmationRequiresTargetToBecomeSolid() {
		assertFalse(BlockInteractionTaskExecutor.placementConfirmed(false));
		assertTrue(BlockInteractionTaskExecutor.placementConfirmed(true));
	}

	@Test
	void batchedRequestPausesWhenSessionGateBlocksActuation() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("session_gate", executor.snapshot().lastPathEvent());
	}

	@Test
	void batchedRequestFailsWhenWorldUnavailable() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("world_unavailable", event.orElseThrow().message());
	}

	private static WorldTaskRequest request() {
		return WorldTaskRequest.useBlock(
			"task-1",
			"job-1",
			new BlockUseStepArgs(
				"minecraft:wheat_seeds",
				List.of(
					new BlockUseStepArgs.Target(new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air"),
					new BlockUseStepArgs.Target(new GoalPosition(2, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
				)
			)
		);
	}

}
