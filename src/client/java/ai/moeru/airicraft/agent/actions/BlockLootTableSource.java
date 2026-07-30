package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Value captured by the Minecraft shell before deterministic loot-rule compilation.
 */
public record BlockLootTableSource(
	String blockId,
	String lootTableId,
	String lootTableJson,
	boolean correctToolRequired,
	List<String> suitableToolItemIds,
	int emptyHandBreakTicks,
	Map<String, Integer> breakTicksByToolItemId
) {
	private static final int LEGACY_BREAK_TICKS = 30;

	public BlockLootTableSource {
		blockId = require(blockId, "blockId");
		lootTableId = require(lootTableId, "lootTableId");
		lootTableJson = require(lootTableJson, "lootTableJson");
		suitableToolItemIds = suitableToolItemIds == null ? List.of() : suitableToolItemIds.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.sorted()
			.toList();
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

	public BlockLootTableSource(
		String blockId,
		String lootTableId,
		String lootTableJson,
		boolean correctToolRequired,
		List<String> suitableToolItemIds
	) {
		this(
			blockId,
			lootTableId,
			lootTableJson,
			correctToolRequired,
			suitableToolItemIds,
			LEGACY_BREAK_TICKS,
			legacyToolBreakTicks(suitableToolItemIds)
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

	private static String require(String value, String fieldName) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return normalized;
	}
}
