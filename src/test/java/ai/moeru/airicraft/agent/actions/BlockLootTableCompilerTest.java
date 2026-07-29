package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLootTableCompilerTest {
	@Test
	void compilesSeagrassShearsRequirementWithoutItemSpecificCode() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:seagrass",
			"minecraft:blocks/seagrass",
			"""
				{
				  "pools": [{
				    "conditions": [{
				      "condition": "minecraft:match_tool",
				      "predicate": {"items": "minecraft:shears"}
				    }],
				    "entries": [{"type": "minecraft:item", "name": "minecraft:seagrass"}]
				  }]
				}
				""",
			false,
			List.of()
		)), Map.of());

		BlockAcquisitionRule rule = index.rulesForOutput("minecraft:seagrass").getFirst();
		assertEquals("minecraft:seagrass", rule.blockId());
		assertEquals(List.of("minecraft:shears"), rule.usableToolItemIds());
		assertFalse(rule.emptyHandAllowed());
	}

	@Test
	void combinesStoneLootWithCorrectToolMetadata() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:stone",
			"minecraft:blocks/stone",
			"""
				{
				  "pools": [{
				    "entries": [{
				      "type": "minecraft:alternatives",
				      "children": [
				        {
				          "type": "minecraft:item",
				          "conditions": [{
				            "condition": "minecraft:match_tool",
				            "predicate": {"predicates": {"minecraft:enchantments": []}}
				          }],
				          "name": "minecraft:stone"
				        },
				        {"type": "minecraft:item", "name": "minecraft:cobblestone"}
				      ]
				    }]
				  }]
				}
				""",
			true,
			List.of("minecraft:iron_pickaxe", "minecraft:wooden_pickaxe")
		)), Map.of());

		BlockAcquisitionRule cobblestone = index.rulesForOutput("minecraft:cobblestone").getFirst();
		assertEquals(List.of("minecraft:iron_pickaxe", "minecraft:wooden_pickaxe"), cobblestone.usableToolItemIds());
		assertFalse(cobblestone.emptyHandAllowed());
		assertTrue(index.rulesForOutput("minecraft:stone").isEmpty(), "unsupported enchantment-only routes stay conservative");
	}

	@Test
	void preservesProbabilisticHandHarvestedDrops() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:short_grass",
			"minecraft:blocks/short_grass",
			"""
				{
				  "pools": [{
				    "entries": [{
				      "type": "minecraft:item",
				      "conditions": [{"condition": "minecraft:random_chance", "chance": 0.125}],
				      "name": "minecraft:wheat_seeds"
				    }]
				  }]
				}
				""",
			false,
			List.of()
		)), Map.of());

		BlockAcquisitionRule seeds = index.rulesForOutput("minecraft:wheat_seeds").getFirst();
		assertTrue(seeds.emptyHandAllowed());
		assertTrue(seeds.probabilistic());
		assertEquals(List.of("minecraft:short_grass"), index.sourceBlockIdsForOutput("minecraft:wheat_seeds"));
		assertEquals(java.util.Set.of("minecraft:wheat_seeds"), index.matchingOutputItemIds(List.of("minecraft:short_grass")));
	}
}
