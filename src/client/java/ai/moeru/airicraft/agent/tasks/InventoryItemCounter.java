package ai.moeru.airicraft.agent.tasks;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

public final class InventoryItemCounter {
	public Map<String, Integer> count(PlayerInventory inventory) {
		Map<String, Integer> counts = new HashMap<>();
		if (inventory == null) {
			return Map.of();
		}
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String itemId = Registries.ITEM.getId(stack.getItem()).toString();
			counts.merge(itemId, stack.getCount(), Integer::sum);
		}
		return Map.copyOf(counts);
	}
}
