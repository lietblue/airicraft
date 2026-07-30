package ai.moeru.airicraft.agent.actions;

/**
 * Pure baseline form of Minecraft's block-breaking progress formula.
 */
final class BlockMiningTime {
	private BlockMiningTime() {
	}

	static int baselineBreakTicks(double hardness, double miningSpeed, boolean harvestable) {
		if (!Double.isFinite(hardness) || hardness < 0.0) {
			return Integer.MAX_VALUE;
		}
		if (hardness == 0.0) {
			return 1;
		}
		double normalizedSpeed = Double.isFinite(miningSpeed) && miningSpeed > 0.0 ? miningSpeed : 1.0;
		double ticks = hardness * (harvestable ? 30.0 : 100.0) / normalizedSpeed;
		return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) Math.ceil(ticks));
	}
}
