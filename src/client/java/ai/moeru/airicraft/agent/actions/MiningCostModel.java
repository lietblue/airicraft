package ai.moeru.airicraft.agent.actions;

final class MiningCostModel {
	static final int BASE_MINING_COST = 20;
	static final int BREAK_TICKS_PER_COST = 20;
	// Searching beyond a known-empty local scan remains possible, but should lose to even
	// fairly inefficient observed sources. Because this is additive, it does not amplify
	// block hardness; expected drop attempts are the only multiplier.
	static final int ABSENT_NEARBY_BLOCK_PENALTY = 2048;

	private MiningCostModel() {
	}

	static int workCost(int breakTicks, int expectedBreakCount, int availabilityPenalty) {
		int perBreakCost = saturatingAdd(
			BASE_MINING_COST,
			breakTickCost(breakTicks),
			Math.max(0, availabilityPenalty)
		);
		return saturatingMultiply(perBreakCost, Math.max(0, expectedBreakCount));
	}

	static int breakTickCost(int breakTicks) {
		return ceilDiv(Math.max(1, breakTicks), BREAK_TICKS_PER_COST);
	}

	static int availabilityPenalty(boolean availabilityObserved, int nearbyBlockCount, int expectedBreakCount) {
		if (!availabilityObserved) {
			return 0;
		}
		if (nearbyBlockCount <= 0) {
			return ABSENT_NEARBY_BLOCK_PENALTY;
		}
		return Math.max(0, ceilDiv(expectedBreakCount, nearbyBlockCount) - 1);
	}

	private static int ceilDiv(int numerator, int denominator) {
		if (numerator <= 0) {
			return 0;
		}
		if (denominator <= 0) {
			return Integer.MAX_VALUE;
		}
		return (int) Math.min(Integer.MAX_VALUE, ((long) numerator + denominator - 1L) / denominator);
	}

	private static int saturatingAdd(int... values) {
		long result = 0L;
		for (int value : values) {
			result += Math.max(0, value);
			if (result >= Integer.MAX_VALUE) {
				return Integer.MAX_VALUE;
			}
		}
		return (int) result;
	}

	private static int saturatingMultiply(int left, int right) {
		if (left <= 0 || right <= 0) {
			return 0;
		}
		long result = (long) left * right;
		return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
	}
}
