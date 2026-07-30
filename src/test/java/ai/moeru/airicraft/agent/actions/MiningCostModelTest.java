package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningCostModelTest {
	@Test
	void miningHasABaseCostAndCompressedBreakTimeCost() {
		assertEquals(21, MiningCostModel.workCost(1, 1, 0));
		assertEquals(21, MiningCostModel.workCost(20, 1, 0));
		assertEquals(23, MiningCostModel.workCost(60, 1, 0));
		assertTrue(MiningCostModel.workCost(60, 1, 0) > MiningCostModel.workCost(1, 1, 0));
	}

	@Test
	void expectedDropAttemptsMultiplyTheFinalPerBreakCost() {
		int deterministic = MiningCostModel.workCost(10, 1, 7);
		int twentyPercentDrop = MiningCostModel.workCost(10, 5, 7);

		assertEquals(deterministic * 5, twentyPercentDrop);
	}

	@Test
	void availabilityIsAnAdditivePenalty() {
		assertEquals(0, MiningCostModel.availabilityPenalty(false, 0, 10));
		assertEquals(0, MiningCostModel.availabilityPenalty(true, 10, 10));
		assertEquals(9, MiningCostModel.availabilityPenalty(true, 1, 10));
		assertEquals(2048, MiningCostModel.availabilityPenalty(true, 0, 10));
		assertEquals(
			MiningCostModel.BASE_MINING_COST + MiningCostModel.breakTickCost(40) + 9,
			MiningCostModel.workCost(40, 1, 9)
		);
	}
}
