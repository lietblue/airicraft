package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinedBlockDropMapper {
	private MinedBlockDropMapper() {
	}

	public static Set<String> matchingInventoryItemIds(List<String> blockIds) {
		return ResourceGatheringCatalog.matchingInventoryItemIds(blockIds);
	}

	public static List<String> sourceBlockIdsForInventoryItem(String itemId) {
		return ResourceGatheringCatalog.sourceBlockIdsForInventoryItem(itemId);
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
