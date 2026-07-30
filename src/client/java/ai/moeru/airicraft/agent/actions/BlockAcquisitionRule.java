package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable block-harvest knowledge compiled from a loaded loot table and block/tool metadata.
 */
public record BlockAcquisitionRule(
	String blockId,
	String outputItemId,
	List<String> usableToolItemIds,
	boolean emptyHandAllowed,
	boolean probabilistic,
	String lootTableId,
	boolean dropEstimateKnown,
	double dropProbability,
	double expectedDropsPerBreak,
	int emptyHandBreakTicks,
	Map<String, Integer> breakTicksByToolItemId
) {
	private static final int LEGACY_BREAK_TICKS = 30;

	public BlockAcquisitionRule {
		blockId = requireId(blockId, "blockId");
		outputItemId = requireId(outputItemId, "outputItemId");
		usableToolItemIds = usableToolItemIds == null ? List.of() : usableToolItemIds.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.sorted()
			.toList();
		lootTableId = requireId(lootTableId, "lootTableId");
		if (!emptyHandAllowed && usableToolItemIds.isEmpty()) {
			throw new IllegalArgumentException("A block acquisition rule must allow an empty hand or at least one tool");
		}
		if (!Double.isFinite(dropProbability) || dropProbability <= 0.0 || dropProbability > 1.0) {
			throw new IllegalArgumentException("dropProbability must be finite and in (0, 1]");
		}
		if (!Double.isFinite(expectedDropsPerBreak) || expectedDropsPerBreak <= 0.0) {
			throw new IllegalArgumentException("expectedDropsPerBreak must be finite and positive");
		}
		if (emptyHandBreakTicks <= 0) {
			throw new IllegalArgumentException("emptyHandBreakTicks must be positive");
		}
		TreeMap<String, Integer> normalizedBreakTicks = new TreeMap<>();
		if (breakTicksByToolItemId != null) {
			breakTicksByToolItemId.forEach((itemId, ticks) -> {
				String normalizedItemId = itemId == null ? "" : itemId.trim();
				if (!normalizedItemId.isEmpty() && ticks != null && ticks > 0) {
					normalizedBreakTicks.put(normalizedItemId, ticks);
				}
			});
		}
		breakTicksByToolItemId = Map.copyOf(normalizedBreakTicks);
	}

	/**
	 * Compatibility constructor for hand-authored/test knowledge. Legacy rules receive a neutral,
	 * usable one-drop estimate; only the loot compiler emits explicitly unknown estimates.
	 */
	public BlockAcquisitionRule(
		String blockId,
		String outputItemId,
		List<String> usableToolItemIds,
		boolean emptyHandAllowed,
		boolean probabilistic,
		String lootTableId
	) {
		this(
			blockId,
			outputItemId,
			usableToolItemIds,
			emptyHandAllowed,
			probabilistic,
			lootTableId,
			true,
			1.0,
			1.0,
			LEGACY_BREAK_TICKS,
			legacyToolBreakTicks(usableToolItemIds)
		);
	}

	private static Map<String, Integer> legacyToolBreakTicks(List<String> toolItemIds) {
		if (toolItemIds == null || toolItemIds.isEmpty()) {
			return Map.of();
		}
		TreeMap<String, Integer> ticks = new TreeMap<>();
		for (String itemId : toolItemIds) {
			if (itemId != null && !itemId.isBlank()) {
				ticks.put(itemId.trim(), LEGACY_BREAK_TICKS);
			}
		}
		return Map.copyOf(ticks);
	}

	private static String requireId(String value, String fieldName) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return normalized;
	}
}
