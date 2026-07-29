package ai.moeru.airicraft.agent.tasks;

import java.util.Map;
import java.util.List;

public record WorldEvidence(
	Map<TaskResourceKind, Integer> inventoryCounts,
	Map<String, Integer> itemCounts,
	Map<String, Integer> nearbyBlocks,
	List<CraftingOpportunity> availableCrafts,
	List<CraftingOpportunity> knownCrafts,
	List<SmeltingOption> availableSmelts,
	List<SmeltingRecipeKnowledge> knownSmelts,
	String dimension,
	int x,
	int y,
	int z,
	String equippedItemId,
	int selectedHotbarSlot,
	List<String> hotbarItems,
	long tick
) {
	public WorldEvidence {
		inventoryCounts = inventoryCounts == null ? Map.of() : Map.copyOf(inventoryCounts);
		itemCounts = itemCounts == null ? Map.of() : Map.copyOf(itemCounts);
		nearbyBlocks = nearbyBlocks == null ? Map.of() : Map.copyOf(nearbyBlocks);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
		knownCrafts = knownCrafts == null ? List.of() : List.copyOf(knownCrafts);
		availableSmelts = availableSmelts == null ? List.of() : List.copyOf(availableSmelts);
		knownSmelts = knownSmelts == null ? List.of() : List.copyOf(knownSmelts);
		hotbarItems = hotbarItems == null ? List.of() : List.copyOf(hotbarItems);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> itemCounts,
		Map<String, Integer> nearbyBlocks,
		List<CraftingOpportunity> availableCrafts,
		List<CraftingOpportunity> knownCrafts,
		List<SmeltingOption> availableSmelts,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		int selectedHotbarSlot,
		List<String> hotbarItems,
		long tick
	) {
		this(inventoryCounts, itemCounts, nearbyBlocks, availableCrafts, knownCrafts, availableSmelts, List.of(), dimension, x, y, z, equippedItemId, selectedHotbarSlot, hotbarItems, tick);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> itemCounts,
		Map<String, Integer> nearbyBlocks,
		List<CraftingOpportunity> availableCrafts,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		long tick
	) {
		this(inventoryCounts, itemCounts, nearbyBlocks, availableCrafts, List.of(), List.of(), List.of(), dimension, x, y, z, equippedItemId, -1, List.of(), tick);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> itemCounts,
		Map<String, Integer> nearbyBlocks,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		long tick
	) {
		this(inventoryCounts, itemCounts, nearbyBlocks, List.of(), List.of(), List.of(), List.of(), dimension, x, y, z, equippedItemId, -1, List.of(), tick);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> itemCounts,
		Map<String, Integer> nearbyBlocks,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		int selectedHotbarSlot,
		List<String> hotbarItems,
		long tick
	) {
		this(inventoryCounts, itemCounts, nearbyBlocks, List.of(), List.of(), List.of(), List.of(), dimension, x, y, z, equippedItemId, selectedHotbarSlot, hotbarItems, tick);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> nearbyBlocks,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		long tick
	) {
		this(inventoryCounts, Map.of(), nearbyBlocks, List.of(), List.of(), List.of(), List.of(), dimension, x, y, z, equippedItemId, -1, List.of(), tick);
	}
}
