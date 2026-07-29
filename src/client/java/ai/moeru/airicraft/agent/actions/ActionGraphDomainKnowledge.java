package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
	private static final List<CraftingOpportunity> SURVIVAL_CRAFTS = buildSurvivalCrafts();
	private static final List<SmeltingRecipe> SURVIVAL_SMELTS = List.of(
		new SmeltingRecipe(
			"inferred:minecraft_raw_iron_to_minecraft_iron_ingot",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			64,
			"minecraft:furnace",
			1
		)
	);

	private ActionGraphDomainKnowledge() {
	}

	static List<CraftingOpportunity> survivalCrafts() {
		return SURVIVAL_CRAFTS;
	}

	static List<SmeltingRecipe> survivalSmelts() {
		return SURVIVAL_SMELTS;
	}

	static List<String> plankItemIds() {
		return PLANK_ITEM_IDS;
	}

	static List<String> logItemIds() {
		return LOG_ITEM_IDS;
	}

	private static List<CraftingOpportunity> buildSurvivalCrafts() {
		ArrayList<CraftingOpportunity> crafts = new ArrayList<>();
		for (int index = 0; index < Math.min(LOG_ITEM_IDS.size(), PLANK_ITEM_IDS.size()); index++) {
			String log = LOG_ITEM_IDS.get(index);
			String planks = PLANK_ITEM_IDS.get(index);
			crafts.add(recipe(planks, 4, CraftingGridKind.PLAYER_2X2, log));
			crafts.add(recipe("minecraft:stick", 4, CraftingGridKind.PLAYER_2X2, planks, planks));
			crafts.add(recipe("minecraft:crafting_table", 1, CraftingGridKind.PLAYER_2X2, planks, planks, planks, planks));
			crafts.add(recipe(
				"minecraft:wooden_pickaxe",
				1,
				CraftingGridKind.WORKBENCH_3X3,
				planks,
				planks,
				planks,
				"minecraft:stick",
				"minecraft:stick"
			));
			crafts.add(recipe(
				"minecraft:wooden_hoe",
				1,
				CraftingGridKind.WORKBENCH_3X3,
				planks,
				planks,
				"minecraft:stick",
				"minecraft:stick"
			));
		}
		crafts.add(recipe(
			"minecraft:stone_pickaxe",
			1,
			CraftingGridKind.WORKBENCH_3X3,
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:stick",
			"minecraft:stick"
		));
		crafts.add(recipe(
			"minecraft:furnace",
			1,
			CraftingGridKind.WORKBENCH_3X3,
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone",
			"minecraft:cobblestone"
		));
		crafts.add(recipe(
			"minecraft:iron_pickaxe",
			1,
			CraftingGridKind.WORKBENCH_3X3,
			"minecraft:iron_ingot",
			"minecraft:iron_ingot",
			"minecraft:iron_ingot",
			"minecraft:stick",
			"minecraft:stick"
		));
		return List.copyOf(crafts);
	}

	private static CraftingOpportunity recipe(
		String outputItemId,
		int outputCount,
		CraftingGridKind gridKind,
		String... inputItemIds
	) {
		List<String> inputs = List.of(inputItemIds);
		return new CraftingOpportunity(recipeId(inputs, outputItemId), outputItemId, outputCount, inputs, gridKind);
	}

	private static String recipeId(List<String> inputItemIds, String outputItemId) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String inputItemId : inputItemIds) {
			counts.merge(inputItemId, 1, Integer::sum);
		}
		String inputs = counts.entrySet().stream()
			.map(entry -> {
				String segment = recipeIdSegment(entry.getKey());
				return entry.getValue() == 1 ? segment : segment + "_x" + entry.getValue();
			})
			.filter(segment -> !segment.isBlank())
			.collect(Collectors.joining("_and_"));
		return recipeIdSegment(inputs + "_to_" + recipeIdSegment(outputItemId));
	}

	private static String recipeIdSegment(String itemId) {
		String display = itemId == null ? "" : itemId;
		if (display.startsWith("minecraft:")) {
			display = display.substring("minecraft:".length());
		}
		return display.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_+|_+$", "");
	}

	record SmeltingRecipe(
		String optionId,
		String inputItemId,
		String outputItemId,
		int outputCount,
		int maxInputQuantity,
		String stationItemId,
		int stationItemCount
	) {
	}
}
