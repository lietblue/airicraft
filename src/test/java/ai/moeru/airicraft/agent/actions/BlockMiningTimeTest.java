package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockMiningTimeTest {
	@Test
	void matchesVanillaBaselineProgressFormula() {
		assertEquals(60, BlockMiningTime.baselineBreakTicks(2.0, 1.0, true));
		assertEquals(12, BlockMiningTime.baselineBreakTicks(1.5, 4.0, true));
		assertEquals(150, BlockMiningTime.baselineBreakTicks(1.5, 1.0, false));
		assertEquals(1, BlockMiningTime.baselineBreakTicks(0.0, 1.0, true));
		assertEquals(Integer.MAX_VALUE, BlockMiningTime.baselineBreakTicks(-1.0, 1.0, true));
	}
}
