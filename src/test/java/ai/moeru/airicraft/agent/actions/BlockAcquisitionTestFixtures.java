package ai.moeru.airicraft.agent.actions;

import java.util.ArrayList;
import java.util.List;

public final class BlockAcquisitionTestFixtures {
	private BlockAcquisitionTestFixtures() {
	}

	public static BlockAcquisitionIndex survival() {
		ArrayList<BlockAcquisitionRule> rules = new ArrayList<>();
		for (String logItemId : ActionGraphDomainKnowledge.logItemIds()) {
			rules.add(handRule(logItemId, logItemId));
		}
		rules.add(handRule("minecraft:dirt", "minecraft:dirt"));
		rules.add(handRule("minecraft:grass_block", "minecraft:dirt"));
		List<String> woodenPickaxes = List.of(
			"minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
			"minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe", "minecraft:golden_pickaxe"
		);
		List<String> stonePickaxes = List.of(
			"minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"
		);
		List<String> ironPickaxes = List.of(
			"minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"
		);
		addToolRules(rules, List.of("minecraft:stone"), "minecraft:cobblestone", woodenPickaxes);
		addToolRules(rules, List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), "minecraft:coal", woodenPickaxes);
		addToolRules(rules, List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), "minecraft:raw_iron", stonePickaxes);
		addToolRules(rules, List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore"), "minecraft:raw_copper", stonePickaxes);
		addToolRules(rules, List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"), "minecraft:raw_gold", ironPickaxes);
		addToolRules(rules, List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), "minecraft:diamond", ironPickaxes);
		addToolRules(rules, List.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"), "minecraft:emerald", ironPickaxes);
		addToolRules(rules, List.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"), "minecraft:redstone", ironPickaxes);
		addToolRules(rules, List.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"), "minecraft:lapis_lazuli", ironPickaxes);
		addToolRules(rules, List.of("minecraft:nether_quartz_ore"), "minecraft:quartz", woodenPickaxes);
		rules.add(new BlockAcquisitionRule(
			"minecraft:seagrass", "minecraft:seagrass", List.of("minecraft:shears"), false, false, "minecraft:blocks/seagrass"
		));
		return BlockAcquisitionIndex.of(rules);
	}

	private static void addToolRules(
		List<BlockAcquisitionRule> rules,
		List<String> blockIds,
		String outputItemId,
		List<String> toolItemIds
	) {
		for (String blockId : blockIds) {
			rules.add(new BlockAcquisitionRule(blockId, outputItemId, toolItemIds, false, false, "test:" + blockId.substring(10)));
		}
	}

	private static BlockAcquisitionRule handRule(String blockId, String outputItemId) {
		return new BlockAcquisitionRule(blockId, outputItemId, List.of(), true, false, "test:" + blockId.substring(10));
	}
}
