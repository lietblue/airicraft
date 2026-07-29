package ai.moeru.airicraft.agent.actions;

import java.util.List;

/**
 * Value captured by the Minecraft shell before deterministic loot-rule compilation.
 */
public record BlockLootTableSource(
	String blockId,
	String lootTableId,
	String lootTableJson,
	boolean correctToolRequired,
	List<String> suitableToolItemIds
) {
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
	}

	private static String require(String value, String fieldName) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return normalized;
	}
}
