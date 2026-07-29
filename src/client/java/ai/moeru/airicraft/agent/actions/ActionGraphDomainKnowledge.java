package ai.moeru.airicraft.agent.actions;

import java.util.List;

final class ActionGraphDomainKnowledge {
	private static final List<String> PLANK_ITEM_IDS = List.of(
		"minecraft:oak_planks",
		"minecraft:spruce_planks",
		"minecraft:birch_planks",
		"minecraft:jungle_planks",
		"minecraft:acacia_planks",
		"minecraft:dark_oak_planks",
		"minecraft:mangrove_planks",
		"minecraft:cherry_planks",
		"minecraft:pale_oak_planks"
	);
	private static final List<String> LOG_ITEM_IDS = List.of(
		"minecraft:oak_log",
		"minecraft:spruce_log",
		"minecraft:birch_log",
		"minecraft:jungle_log",
		"minecraft:acacia_log",
		"minecraft:dark_oak_log",
		"minecraft:mangrove_log",
		"minecraft:cherry_log",
		"minecraft:pale_oak_log"
	);
	private ActionGraphDomainKnowledge() {
	}

	static List<String> plankItemIds() {
		return PLANK_ITEM_IDS;
	}

	static List<String> logItemIds() {
		return LOG_ITEM_IDS;
	}

}
