package ai.moeru.airicraft.agent.tasks;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

public final class InventoryResourceCounter {
	public int count(Iterable<ItemStack> stacks, TaskResourceKind resourceKind) {
		int total = 0;
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (accepts(resourceKind, Registries.ITEM.getId(stack.getItem()).toString())) {
				total += stack.getCount();
			}
		}
		return total;
	}

	static boolean accepts(TaskResourceKind resourceKind, String itemId) {
		if (resourceKind != TaskResourceKind.WOOD_LOGS) {
			return false;
		}
		return switch (itemId) {
			case "minecraft:oak_log",
				"minecraft:birch_log",
				"minecraft:spruce_log",
				"minecraft:jungle_log",
				"minecraft:acacia_log",
				"minecraft:dark_oak_log",
				"minecraft:mangrove_log",
				"minecraft:cherry_log" -> true;
			default -> false;
		};
	}
}
