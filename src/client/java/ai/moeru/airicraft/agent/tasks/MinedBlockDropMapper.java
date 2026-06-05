package ai.moeru.airicraft.agent.tasks;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinedBlockDropMapper {
	private static final Map<String, List<String>> COMMON_DROPS = Map.ofEntries(
		Map.entry("minecraft:stone", List.of("minecraft:cobblestone")),
		Map.entry("minecraft:grass_block", List.of("minecraft:dirt")),
		Map.entry("minecraft:iron_ore", List.of("minecraft:raw_iron")),
		Map.entry("minecraft:deepslate_iron_ore", List.of("minecraft:raw_iron")),
		Map.entry("minecraft:copper_ore", List.of("minecraft:raw_copper")),
		Map.entry("minecraft:deepslate_copper_ore", List.of("minecraft:raw_copper")),
		Map.entry("minecraft:gold_ore", List.of("minecraft:raw_gold")),
		Map.entry("minecraft:deepslate_gold_ore", List.of("minecraft:raw_gold")),
		Map.entry("minecraft:nether_gold_ore", List.of("minecraft:gold_nugget")),
		Map.entry("minecraft:coal_ore", List.of("minecraft:coal")),
		Map.entry("minecraft:deepslate_coal_ore", List.of("minecraft:coal")),
		Map.entry("minecraft:diamond_ore", List.of("minecraft:diamond")),
		Map.entry("minecraft:deepslate_diamond_ore", List.of("minecraft:diamond")),
		Map.entry("minecraft:emerald_ore", List.of("minecraft:emerald")),
		Map.entry("minecraft:deepslate_emerald_ore", List.of("minecraft:emerald")),
		Map.entry("minecraft:redstone_ore", List.of("minecraft:redstone")),
		Map.entry("minecraft:deepslate_redstone_ore", List.of("minecraft:redstone")),
		Map.entry("minecraft:lapis_ore", List.of("minecraft:lapis_lazuli")),
		Map.entry("minecraft:deepslate_lapis_ore", List.of("minecraft:lapis_lazuli")),
		Map.entry("minecraft:nether_quartz_ore", List.of("minecraft:quartz"))
	);

	private MinedBlockDropMapper() {
	}

	public static Set<String> matchingInventoryItemIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return Set.of();
		}
		LinkedHashSet<String> itemIds = new LinkedHashSet<>();
		for (String blockId : blockIds) {
			if (blockId == null || blockId.isBlank()) {
				continue;
			}
			String normalized = blockId.trim();
			itemIds.add(normalized);
			itemIds.addAll(COMMON_DROPS.getOrDefault(normalized, List.of()));
		}
		return Collections.unmodifiableSet(itemIds);
	}

	public static int matchingInventoryItemCount(Map<String, Integer> itemCounts, List<String> blockIds) {
		if (itemCounts == null || itemCounts.isEmpty()) {
			return 0;
		}
		int total = 0;
		for (String itemId : matchingInventoryItemIds(blockIds)) {
			total += itemCounts.getOrDefault(itemId, 0);
		}
		return total;
	}
}
