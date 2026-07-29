package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedHarvestPolicyTest {
	@Test
	void classifiesFluidContainedAndSubmergedSourcesWithoutResourceNames() {
		assertEquals(BoundedHarvestPolicy.SourceEnvironment.FLUID_CONTAINED, BoundedHarvestPolicy.classify(true, true));
		assertEquals(BoundedHarvestPolicy.SourceEnvironment.SUBMERGED, BoundedHarvestPolicy.classify(false, true));
		assertEquals(BoundedHarvestPolicy.SourceEnvironment.DRY, BoundedHarvestPolicy.classify(false, false));
	}

	@Test
	void selectsOnlyNearestTargetsInsideBoundedLocalSearch() {
		BoundedHarvestPolicy.Position origin = new BoundedHarvestPolicy.Position(0, 64, 0);
		List<BoundedHarvestPolicy.Target> selected = BoundedHarvestPolicy.selectBatch(List.of(
			target(5, 62, 0),
			target(2, 63, 0),
			target(21, 64, 0),
			target(3, 63, 0),
			target(4, 63, 0),
			target(6, 63, 0)
		), origin);

		assertEquals(4, selected.size());
		assertEquals(new BoundedHarvestPolicy.Position(2, 63, 0), selected.getFirst().position());
		assertFalse(selected.stream().anyMatch(candidate -> candidate.position().x() == 21));
	}

	@Test
	void surfacesWithReserveAndWaitsForAirBeforeResuming() {
		assertTrue(BoundedHarvestPolicy.shouldSurface(true, BoundedHarvestPolicy.AIR_RESERVE_TICKS, 300));
		assertFalse(BoundedHarvestPolicy.shouldSurface(false, 0, 300));
		assertFalse(BoundedHarvestPolicy.mayResumeHarvest(true, 300, 300));
		assertFalse(BoundedHarvestPolicy.mayResumeHarvest(false, 250, 300));
		assertTrue(BoundedHarvestPolicy.mayResumeHarvest(false, 280, 300));
	}

	@Test
	void distinguishesSuccessFromSupportedResourceMissingNearby() {
		assertEquals(BoundedHarvestPolicy.TerminalDecision.COMPLETE, BoundedHarvestPolicy.terminalDecision(20, 20, 0));
		assertEquals(BoundedHarvestPolicy.TerminalDecision.RESOURCE_NOT_FOUND_NEARBY, BoundedHarvestPolicy.terminalDecision(19, 20, 0));
		assertEquals(BoundedHarvestPolicy.TerminalDecision.CONTINUE, BoundedHarvestPolicy.terminalDecision(19, 20, 1));
	}

	@Test
	void approachesDropsUntilInventoryConfirmsCollection() {
		assertEquals(
			BoundedHarvestPolicy.PickupDecision.APPROACH,
			BoundedHarvestPolicy.pickupDecision(0, 0, 1, true)
		);
		assertEquals(
			BoundedHarvestPolicy.PickupDecision.COLLECTED,
			BoundedHarvestPolicy.pickupDecision(0, 4, 0, true)
		);
	}

	@Test
	void timesOutVisibleOrMissingDropsAsUnreachable() {
		assertEquals(
			BoundedHarvestPolicy.PickupDecision.UNREACHABLE,
			BoundedHarvestPolicy.pickupDecision(0, 0, 0, true)
		);
		assertEquals(
			BoundedHarvestPolicy.PickupDecision.WAIT,
			BoundedHarvestPolicy.pickupDecision(0, 0, 1, false)
		);
		assertEquals(
			BoundedHarvestPolicy.PickupDecision.UNREACHABLE,
			BoundedHarvestPolicy.pickupDecision(0, 0, 0, false)
		);
	}

	private static BoundedHarvestPolicy.Target target(int x, int y, int z) {
		return new BoundedHarvestPolicy.Target(
			new BoundedHarvestPolicy.Position(x, y, z),
			"minecraft:test_block",
			BoundedHarvestPolicy.SourceEnvironment.FLUID_CONTAINED
		);
	}
}
