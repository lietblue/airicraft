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
			List.of("minecraft:iron_pickaxe", "minecraft:wooden_pickaxe"),
			150,
			Map.of("minecraft:iron_pickaxe", 6, "minecraft:wooden_pickaxe", 23)
		)), Map.of());

		BlockAcquisitionRule cobblestone = index.rulesForOutput("minecraft:cobblestone").getFirst();
		assertEquals(List.of("minecraft:iron_pickaxe", "minecraft:wooden_pickaxe"), cobblestone.usableToolItemIds());
		assertFalse(cobblestone.emptyHandAllowed());
		assertEquals(150, cobblestone.emptyHandBreakTicks());
		assertEquals(Map.of("minecraft:iron_pickaxe", 6, "minecraft:wooden_pickaxe", 23), cobblestone.breakTicksByToolItemId());
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
		assertTrue(seeds.dropEstimateKnown());
		assertEquals(0.125, seeds.dropProbability(), 0.000_001);
		assertEquals(0.125, seeds.expectedDropsPerBreak(), 0.000_001);
		assertEquals(List.of("minecraft:short_grass"), index.sourceBlockIdsForOutput("minecraft:wheat_seeds"));
		assertEquals(java.util.Set.of("minecraft:wheat_seeds"), index.matchingOutputItemIds(List.of("minecraft:short_grass")));
	}

	@Test
	void compilesVanillaLeafStickExpectedYield() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:oak_leaves",
			"minecraft:blocks/oak_leaves",
			"""
				{
				  "pools": [{
				    "entries": [{
				      "type": "minecraft:item",
				      "conditions": [{
				        "condition": "minecraft:table_bonus",
				        "enchantment": "minecraft:fortune",
				        "chances": [0.02, 0.022222223, 0.025, 0.033333335, 0.1]
				      }],
				      "functions": [{
				        "function": "minecraft:set_count",
				        "count": {"type": "minecraft:uniform", "min": 1, "max": 2}
				      }],
				      "name": "minecraft:stick"
				    }]
				  }]
				}
				""",
			false,
			List.of(),
			6,
			Map.of("minecraft:shears", 2)
		)), Map.of());

		BlockAcquisitionRule sticks = index.rulesForOutput("minecraft:stick").getFirst();
		assertTrue(sticks.dropEstimateKnown());
		assertEquals(0.02, sticks.dropProbability(), 0.000_001);
		assertEquals(0.03, sticks.expectedDropsPerBreak(), 0.000_001);
		assertEquals(6, sticks.emptyHandBreakTicks());
	}

	@Test
	void usesUnenchantedTableBonusChance() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:oak_leaves",
			"minecraft:blocks/oak_leaves",
			"""
				{
				  "pools": [{
				    "entries": [{
				      "type": "minecraft:item",
				      "conditions": [{
				        "condition": "minecraft:table_bonus",
				        "chances": [0.02, 0.022222, 0.025]
				      }],
				      "name": "minecraft:stick"
				    }]
				  }]
				}
				""",
			false,
			List.of()
		)), Map.of());

		BlockAcquisitionRule sticks = index.rulesForOutput("minecraft:stick").getFirst();
		assertTrue(sticks.dropEstimateKnown());
		assertEquals(0.02, sticks.dropProbability(), 0.000_001);
		assertEquals(0.02, sticks.expectedDropsPerBreak(), 0.000_001);
	}

	@Test
	void keepsUnsupportedRandomConditionsExplicitlyUnknown() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:test_block",
			"minecraft:blocks/test_block",
			"""
				{
				  "pools": [{
				    "entries": [{
				      "type": "minecraft:item",
				      "conditions": [{"condition": "minecraft:random_chance_with_looting"}],
				      "name": "minecraft:test_drop"
				    }]
				  }]
				}
				""",
			false,
			List.of()
		)), Map.of());

		BlockAcquisitionRule rule = index.rulesForOutput("minecraft:test_drop").getFirst();
		assertTrue(rule.probabilistic());
		assertFalse(rule.dropEstimateKnown());
	}

	@Test
	void exposesFasterOptionalToolsForHandHarvestableBlocks() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:oak_log",
			"minecraft:blocks/oak_log",
			"""
				{
				  "pools": [{
				    "entries": [{"type": "minecraft:item", "name": "minecraft:oak_log"}]
				  }]
				}
				""",
			false,
			List.of(),
			60,
			Map.of("minecraft:iron_axe", 5, "minecraft:iron_pickaxe", 60)
		)), Map.of());

		BlockAcquisitionRule log = index.rulesForOutput("minecraft:oak_log").getFirst();
		assertTrue(log.emptyHandAllowed());
		assertEquals(List.of("minecraft:iron_axe"), log.usableToolItemIds());
		assertEquals(Map.of("minecraft:iron_axe", 5), log.breakTicksByToolItemId());
	}

	@Test
	void doesNotOfferToolsExplicitlyExcludedByTheLootPath() {
		BlockAcquisitionIndex index = BlockLootTableCompiler.compile(List.of(new BlockLootTableSource(
			"minecraft:oak_leaves",
			"minecraft:blocks/oak_leaves",
			"""
				{
				  "pools": [{
				    "conditions": [{
				      "condition": "minecraft:inverted",
				      "term": {
				        "condition": "minecraft:match_tool",
				        "predicate": {"items": "minecraft:shears"}
				      }
				    }],
				    "entries": [{"type": "minecraft:item", "name": "minecraft:stick"}]
				  }]
				}
				""",
			false,
			List.of(),
			6,
			Map.of("minecraft:shears", 2, "minecraft:iron_hoe", 2)
		)), Map.of());

		BlockAcquisitionRule sticks = index.rulesForOutput("minecraft:stick").getFirst();
		assertTrue(sticks.usableToolItemIds().isEmpty());
		assertTrue(sticks.breakTicksByToolItemId().isEmpty());
	}
}
