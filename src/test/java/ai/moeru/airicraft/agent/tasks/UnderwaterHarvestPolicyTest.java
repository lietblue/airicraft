package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderwaterHarvestPolicyTest {
	@Test
	void classifiesOnlyAccessibleDryAndUnderwaterSourcesWithoutResourceNames() {
		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.FLUID_CONTAINED,
			UnderwaterHarvestPolicy.classify(true, true, true).orElseThrow());
		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.DRY,
			UnderwaterHarvestPolicy.classify(false, true, true).orElseThrow());
		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT,
			UnderwaterHarvestPolicy.classify(false, false, true).orElseThrow());
		assertTrue(UnderwaterHarvestPolicy.classify(false, false, false).isEmpty());
	}

	@Test
	void selectsOnlyNearestTargetsInsideLocalSearchBoundary() {
		UnderwaterHarvestPolicy.Position origin = new UnderwaterHarvestPolicy.Position(0, 64, 0);
		List<UnderwaterHarvestPolicy.Target> selected = UnderwaterHarvestPolicy.selectBatch(List.of(
			target(5, 62, 0),
			target(2, 63, 0),
			target(21, 64, 0),
			target(3, 63, 0),
			target(4, 63, 0),
			target(6, 63, 0)
		), origin);

		assertEquals(4, selected.size());
		assertEquals(new UnderwaterHarvestPolicy.Position(2, 63, 0), selected.getFirst().position());
		assertFalse(selected.stream().anyMatch(candidate -> candidate.position().x() == 21));
	}

	@Test
	void prioritizesDryThenWaterAdjacentThenFluidContainedSources() {
		UnderwaterHarvestPolicy.Position origin = new UnderwaterHarvestPolicy.Position(0, 64, 0);
		List<UnderwaterHarvestPolicy.Target> selected = UnderwaterHarvestPolicy.selectBatch(List.of(
			target(1, 64, 0, "minecraft:seagrass", UnderwaterHarvestPolicy.SourceEnvironment.FLUID_CONTAINED),
			target(2, 64, 0, "minecraft:clay", UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT),
			target(8, 64, 0, "minecraft:sand", UnderwaterHarvestPolicy.SourceEnvironment.DRY),
			target(3, 64, 0, "minecraft:sand", UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT)
		), origin);

		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.DRY, selected.get(0).environment());
		assertEquals("minecraft:sand", selected.get(0).blockId());
		assertEquals(new UnderwaterHarvestPolicy.Position(2, 64, 0), selected.get(1).position());
		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.WATER_ADJACENT, selected.get(2).environment());
		assertEquals(UnderwaterHarvestPolicy.SourceEnvironment.FLUID_CONTAINED, selected.get(3).environment());
	}

	@Test
	void approachRequiresProgressWithinEachWindowAndHasAbsoluteTimeout() {
		UnderwaterHarvestPolicy.ApproachProgress stalled = UnderwaterHarvestPolicy.beginApproach(10.0D);
		UnderwaterHarvestPolicy.ApproachUpdate update = null;
		for (int tick = 0; tick < UnderwaterHarvestPolicy.APPROACH_PROGRESS_WINDOW_TICKS; tick++) {
			update = UnderwaterHarvestPolicy.observeApproach(stalled, 9.8D);
			stalled = update.progress();
		}
		assertEquals(UnderwaterHarvestPolicy.ApproachDecision.EXCLUDE_TARGET, update.decision());

		UnderwaterHarvestPolicy.ApproachProgress progressing = UnderwaterHarvestPolicy.beginApproach(10.0D);
		for (int tick = 1; tick < UnderwaterHarvestPolicy.APPROACH_TIMEOUT_TICKS; tick++) {
			double distance = 10.0D - (tick / 20) * UnderwaterHarvestPolicy.APPROACH_MINIMUM_PROGRESS_BLOCKS;
			update = UnderwaterHarvestPolicy.observeApproach(progressing, distance);
			progressing = update.progress();
			assertEquals(UnderwaterHarvestPolicy.ApproachDecision.CONTINUE, update.decision());
		}
		update = UnderwaterHarvestPolicy.observeApproach(progressing, 8.75D);
		assertEquals(UnderwaterHarvestPolicy.ApproachDecision.EXCLUDE_TARGET, update.decision());
	}

	@Test
	void distinguishesMissingSourcesFromAllDiscoveredSourcesBeingExcluded() {
		assertEquals(
			UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_NOT_FOUND_NEARBY,
			UnderwaterHarvestPolicy.sourceExhaustion(0, 0, false)
		);
		assertEquals(
			UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_UNREACHABLE_NEARBY,
			UnderwaterHarvestPolicy.sourceExhaustion(3, 3, true)
		);
	}

	@Test
	void surfacesWithReserveAndWaitsForAirBeforeResuming() {
		assertTrue(UnderwaterHarvestPolicy.shouldSurface(true, UnderwaterHarvestPolicy.AIR_RESERVE_TICKS, 300));
		assertFalse(UnderwaterHarvestPolicy.shouldSurface(false, 0, 300));
		assertFalse(UnderwaterHarvestPolicy.mayResumeHarvest(true, 300, 300));
		assertFalse(UnderwaterHarvestPolicy.mayResumeHarvest(false, 250, 300));
		assertTrue(UnderwaterHarvestPolicy.mayResumeHarvest(false, 280, 300));
	}

	@Test
	void distinguishesSuccessFromSupportedResourceMissingNearby() {
		assertEquals(UnderwaterHarvestPolicy.TerminalDecision.COMPLETE, UnderwaterHarvestPolicy.terminalDecision(20, 20, 0));
		assertEquals(UnderwaterHarvestPolicy.TerminalDecision.RESOURCE_NOT_FOUND_NEARBY, UnderwaterHarvestPolicy.terminalDecision(19, 20, 0));
		assertEquals(UnderwaterHarvestPolicy.TerminalDecision.CONTINUE, UnderwaterHarvestPolicy.terminalDecision(19, 20, 1));
	}

	@Test
	void approachesDropsUntilInventoryConfirmsCollection() {
		assertEquals(
			UnderwaterHarvestPolicy.PickupDecision.APPROACH,
			UnderwaterHarvestPolicy.pickupDecision(0, 0, 1, true)
		);
		assertEquals(
			UnderwaterHarvestPolicy.PickupDecision.COLLECTED,
			UnderwaterHarvestPolicy.pickupDecision(0, 4, 0, true)
		);
	}

	@Test
	void timesOutVisibleOrMissingDropsAsUnreachable() {
		assertEquals(
			UnderwaterHarvestPolicy.PickupDecision.UNREACHABLE,
			UnderwaterHarvestPolicy.pickupDecision(0, 0, 0, true)
		);
		assertEquals(
			UnderwaterHarvestPolicy.PickupDecision.WAIT,
			UnderwaterHarvestPolicy.pickupDecision(0, 0, 1, false)
		);
		assertEquals(
			UnderwaterHarvestPolicy.PickupDecision.UNREACHABLE,
			UnderwaterHarvestPolicy.pickupDecision(0, 0, 0, false)
		);
	}

	@Test
	void airRecoveryPausesPickupTimeout() {
		assertEquals(37, UnderwaterHarvestPolicy.pickupTicksAfterTick(37, true));
		assertEquals(36, UnderwaterHarvestPolicy.pickupTicksAfterTick(37, false));
	}

	@Test
	void steersTowardCenterOfBlockContainingDrop() {
		assertEquals(
			new UnderwaterHarvestPolicy.PickupTarget(34.5D, 60.5D, 58.5D),
			UnderwaterHarvestPolicy.pickupTarget(34.9D, 60.0D, 58.1D)
		);
		assertEquals(
			new UnderwaterHarvestPolicy.PickupTarget(-0.5D, 10.5D, -1.5D),
			UnderwaterHarvestPolicy.pickupTarget(-0.1D, 10.9D, -2.0D)
		);
	}

	private static UnderwaterHarvestPolicy.Target target(int x, int y, int z) {
		return target(x, y, z, UnderwaterHarvestPolicy.SourceEnvironment.FLUID_CONTAINED);
	}

	private static UnderwaterHarvestPolicy.Target target(
		int x,
		int y,
		int z,
		UnderwaterHarvestPolicy.SourceEnvironment environment
	) {
		return target(x, y, z, "minecraft:test_block", environment);
	}

	private static UnderwaterHarvestPolicy.Target target(
		int x,
		int y,
		int z,
		String blockId,
		UnderwaterHarvestPolicy.SourceEnvironment environment
	) {
		return new UnderwaterHarvestPolicy.Target(
			new UnderwaterHarvestPolicy.Position(x, y, z),
			blockId,
			environment
		);
	}
}
