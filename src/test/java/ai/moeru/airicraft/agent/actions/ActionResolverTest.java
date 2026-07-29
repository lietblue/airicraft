package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionResolverTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test
	void resolvesResourceCollectionThroughResourceProvider() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryResource("world-a", "bot", "WOOD_LOGS"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.resourceCollection("WOOD_LOGS", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("resource_provider", step.actionId());
		assertEquals("WOOD_LOGS", step.args().get("resourceKind"));
		assertEquals(2, step.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "resource_provider", "WOOD_LOGS");
	}

	@Test
	void resolvesCatalogedResourceCollectionThroughInventoryMiningProvider() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.resourceCollection("RAW_IRON", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().anyMatch(step -> "collect_resource".equals(step.targetId())));
		assertTrue(result.route().steps().stream().anyMatch(step -> "craft_item".equals(step.targetId())
			&& "minecraft:wooden_pickaxe".equals(step.args().get("itemId"))));
		assertTrue(result.route().steps().stream().anyMatch(step -> "craft_item".equals(step.targetId())
			&& "minecraft:stone_pickaxe".equals(step.args().get("itemId"))));
		assertTrue(result.route().steps().stream().anyMatch(step -> "mine_block".equals(step.targetId())
			&& "minecraft:raw_iron".equals(step.args().get("itemId"))));
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("minecraft:raw_iron", result.route().steps().getLast().args().get("itemId"));
		assertTrace(result.trace(), "route_selected", "resource_provider", "RAW_IRON");
		assertTrace(result.trace(), "route_selected", "mining_provider", "minecraft:raw_iron");
	}

	@Test
	void rejectsUnsupportedResourceCollectionWithTrace() {
		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), new ActionFactStore(), CONTEXT)
			.resolve(ActionGoal.resourceCollection("OBSIDIAN", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrace(result.trace(), "route_candidate_rejected", "resource_provider", "OBSIDIAN");
	}

	@Test
	void resolvesInventoryLogThroughWoodResourceProvider() {
		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), new ActionFactStore(), CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:birch_log", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("resource_provider", step.actionId());
		assertEquals("minecraft:birch_log", step.alternativeId());
		assertEquals("WOOD_LOGS", step.args().get("resourceKind"));
		assertEquals(2, step.args().get("quantity"));
	}

	@Test
	void resolvesBreadFromInventoryWheatWithoutActuating() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(10, result.route().cost());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals(ActionStepKind.PRIMITIVE, craft.kind());
		assertEquals("craft_bread", craft.stepId());
		assertEquals("minecraft:bread", craft.args().get("itemId"));
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "craft_from_inventory_wheat");
		assertTrace(result.trace(), "primitive_planned", "make_bread", "craft_from_inventory_wheat");
	}

	@Test
	void resolvesBreadDeficitWhenSomeBreadAlreadyExists() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "craft_from_inventory_wheat");
	}

	@Test
	void resolvesCraftingFromRecipeFactWithoutItemSpecificActionset() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of(
				"outputItemId", "minecraft:bread",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:wheat", 3)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:bread", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "wheat_wheat_wheat_to_bread");
	}

	@Test
	void recipeProviderUsesGoalDeficitForInputsAndCraftQuantity() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 6),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of(
				"outputItemId", "minecraft:bread",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:wheat", 3)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:bread", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(2, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "wheat_wheat_wheat_to_bread");
	}

	@Test
	void resolvesNestedCraftingRecipesFromLogToSticks() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_log_to_oak_planks"),
			Map.of(
				"outputItemId", "minecraft:oak_planks",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_log", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_planks_x2_to_stick"),
			Map.of(
				"outputItemId", "minecraft:stick",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_planks", 2)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:stick", 4));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("oak_log_to_oak_planks", result.route().steps().get(0).args().get("recipeId"));
		assertEquals("oak_planks_x2_to_stick", result.route().steps().get(1).args().get("recipeId"));
	}

	@Test
	void recipeProviderPrefersObservedWoodVariantAfterGenericCollection() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:crafting_table", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("birch_log_to_birch_planks", result.route().steps().get(0).args().get("recipeId"));
		assertEquals("birch_planks_x4_to_crafting_table", result.route().steps().get(1).args().get("recipeId"));
	}

	@Test
	void recipeProviderUsesObservedLogWhenPlankDeficitRemainsAfterReplan() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("birch_log_to_birch_planks", result.route().steps().get(1).args().get("recipeId"));
		assertEquals("birch_planks_x3_and_stick_x2_to_wooden_pickaxe", result.route().steps().get(2).args().get("recipeId"));
	}

	@Test
	void recipeProviderResolvesWoodenHoeForFarmBootstrap() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_planks"),
			Map.of("count", 7),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:wooden_hoe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("oak_planks_x2_to_stick", result.route().steps().getFirst().args().get("recipeId"));
		assertEquals("oak_planks_x2_and_stick_x2_to_wooden_hoe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderDoesNotBindUnobservedPlankVariantWhenPartialPlanksNeedMoreWood() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertFalse(result.route().steps().stream()
			.anyMatch(step -> "acacia_planks_x3_and_stick_x2_to_wooden_pickaxe".equals(step.args().get("recipeId"))));
		assertEquals("birch_planks_x3_and_stick_x2_to_wooden_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCollectsGenericWoodBeforeChoosingConcreteWoodVariant() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:crafting_table", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().alternativeId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
	}

	@Test
	void resolvesInventoryItemThroughSmeltingRecipeFact() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "smelt_item", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuel = result.route().steps().getFirst();
		assertEquals("WOOD_LOGS", fuel.args().get("resourceKind"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("smelting_provider", smelt.actionId());
		assertEquals("minecraft:raw_iron", smelt.args().get("inputItemId"));
		assertEquals(3, smelt.args().get("inputQuantity"));
		assertEquals("minecraft:oak_log", smelt.args().get("fuelItemId"));
		assertEquals(2, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "route_selected", "smelting_provider", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1");
	}

	@Test
	void smeltingProviderPlansFuelSubgoalWhenFuelIsCraftable() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "smelt_item", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuelCraft = result.route().steps().getFirst();
		assertEquals("minecraft:birch_planks", fuelCraft.args().get("itemId"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("minecraft:birch_planks", smelt.args().get("fuelItemId"));
		assertEquals(2, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "fuel_subgoal_planned", "smelting_provider", "minecraft:birch_planks");
	}

	@Test
	void smeltingProviderPrefersMineableCoalOverWoodFuelWhenPickaxeAvailable() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "smelt_item", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuel = result.route().steps().getFirst();
		assertEquals("minecraft:coal", fuel.args().get("itemId"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("minecraft:coal", smelt.args().get("fuelItemId"));
		assertEquals(1, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "fuel_subgoal_planned", "smelting_provider", "minecraft:coal");
	}

	@Test
	void directSmeltingBeatsReversibleCraftingRecipes() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x9_to_iron_block"),
			Map.of(
				"outputItemId", "minecraft:iron_block",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 9)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_block_to_iron_ingot"),
			Map.of(
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 9,
				"inputCounts", Map.of("minecraft:iron_block", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "smelt_item", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals(3, smelt.args().get("inputQuantity"));
		assertEquals("minecraft:oak_log", smelt.args().get("fuelItemId"));
		assertFalse(result.route().steps().stream().anyMatch(step -> "iron_block_to_iron_ingot".equals(step.args().get("recipeId"))));
	}

	@Test
	void resolvesMinedDropInventoryItemThroughMiningProvider() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep mine = result.route().steps().getFirst();
		assertEquals("mining_provider", mine.actionId());
		assertEquals("minecraft:raw_iron", mine.args().get("itemId"));
		assertEquals(3, mine.args().get("quantity"));
		assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), mine.args().get("blockIds"));
		assertTrace(result.trace(), "route_selected", "mining_provider", "minecraft:raw_iron");
	}

	@Test
	void directMiningBeatsReversibleRawBlockCraftingRecipes() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "raw_iron_x9_to_raw_iron_block"),
			Map.of(
				"outputItemId", "minecraft:raw_iron_block",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:raw_iron", 9)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "raw_iron_block_to_raw_iron"),
			Map.of(
				"outputItemId", "minecraft:raw_iron",
				"outputCount", 9,
				"inputCounts", Map.of("minecraft:raw_iron_block", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep mine = result.route().steps().getFirst();
		assertEquals("minecraft:raw_iron", mine.args().get("itemId"));
		assertEquals(3, mine.args().get("quantity"));
		assertFalse(result.route().steps().stream().anyMatch(step -> "raw_iron_block_to_raw_iron".equals(step.args().get("recipeId"))));
	}

	@Test
	void miningProviderPrependsPickaxePrerequisites() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 6),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "craft_item", "mine_block", "craft_item", "mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("minecraft:wooden_pickaxe", result.route().steps().get(1).args().get("itemId"));
		assertEquals("minecraft:cobblestone", result.route().steps().get(2).args().get("itemId"));
		assertEquals("minecraft:stone_pickaxe", result.route().steps().get(3).args().get("itemId"));
		assertEquals("minecraft:raw_iron", result.route().steps().get(4).args().get("itemId"));
	}

	@Test
	void ironPickaxeRouteSurvivesPartialBirchToolReplan() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "inferred:minecraft_raw_iron_to_minecraft_iron_ingot"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void ironPickaxeRouteSurvivesReplanAfterFurnaceConsumesCobblestone() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		addSurvivalSmeltFacts(facts);
		CraftingOpportunity stonePickaxeRecipe = ActionGraphRecipeFixtures.survivalCrafts().stream()
			.filter(craft -> "minecraft:stone_pickaxe".equals(craft.outputItemId()))
			.findFirst()
			.orElseThrow();
		ActionFactIdentity stonePickaxeRecipeId = ActionFactIdentity.craftRecipe(
			"world-a",
			"bot",
			stonePickaxeRecipe.recipeId()
		);
		facts.upsert(new ActionFact(
			stonePickaxeRecipeId,
			craftPayload(stonePickaxeRecipe),
			ActionFactProvenance.OBSERVED,
			98,
			99
		));
		facts.upsert(new ActionFact(
			stonePickaxeRecipeId,
			craftPayload(stonePickaxeRecipe),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		));
		Map<String, Integer> inventory = Map.of(
			"minecraft:birch_planks", 7,
			"minecraft:furnace", 1,
			"minecraft:stick", 2,
			"minecraft:wooden_pickaxe", 1
		);
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.inventoryItem("world-a", "bot", entry.getKey()),
				Map.of("count", entry.getValue()),
				ActionFactProvenance.OBSERVED,
				90,
				ActionFact.NEVER_STALE
			));
		}

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"mine_block".equals(step.targetId())
				&& "minecraft:cobblestone".equals(step.args().get("itemId"))
		), () -> result.route().toString());
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"craft_item".equals(step.targetId())
				&& "minecraft:stone_pickaxe".equals(step.args().get("itemId"))
		), () -> result.route().toString());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void workbenchSetupPrefersObservedLogSpeciesWhenCarriedPlanksAreInsufficient() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 8),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:spruce_log"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:furnace", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("spruce_log_to_spruce_planks", result.route().steps().getFirst().args().get("recipeId"));
		assertEquals("cobblestone_x8_to_furnace", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCanUseSmeltingProviderForCraftInputs() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2)
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "smelt_item", "collect_smelted_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:oak_log", result.route().steps().get(1).args().get("fuelItemId"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().get(3).args().get("recipeId"));
	}

	@Test
	void workbenchRecipeProviderEnsuresPortableCraftingTableWhenRouteHasNoStation() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:iron_ingot"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "birch_planks_x4_to_crafting_table"),
			Map.of(
				"outputItemId", "minecraft:crafting_table",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:birch_planks", 4),
				"gridKind", "PLAYER_2X2"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:iron_pickaxe", result.route().steps().getFirst().args().get("itemId"));
	}

	@Test
	void workbenchRecipeProviderUsesCarriedCraftingTableAsStation() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:crafting_table"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:iron_ingot"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getFirst().args().get("recipeId"));
	}

	@Test
	void workbenchRecipeProviderDoesNotRepeatStationAlreadyPlannedInRoute() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 8),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:coal"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "birch_planks_x4_to_crafting_table"),
			Map.of(
				"outputItemId", "minecraft:crafting_table",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:birch_planks", 4),
				"gridKind", "PLAYER_2X2"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "cobblestone_x8_to_furnace"),
			Map.of(
				"outputItemId", "minecraft:furnace",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:cobblestone", 8),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:carried_furnace-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"stationItemId", "minecraft:furnace",
				"stationItemCount", 1
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(0, result.route().steps().stream()
			.filter(step -> "minecraft:crafting_table".equals(step.args().get("itemId")))
			.count());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCanUseMiningAndSmeltingProvidersForCraftInputs() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2)
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "mine_block", "smelt_item", "collect_smelted_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:raw_iron", result.route().steps().getFirst().args().get("itemId"));
		assertEquals("minecraft:coal", result.route().steps().get(1).args().get("itemId"));
		assertEquals("minecraft:coal", result.route().steps().get(2).args().get("fuelItemId"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().get(4).args().get("recipeId"));
	}

	@Test
	void miningProviderPreservesAbsoluteInventoryTargetForPartialCobblestoneDeficit() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wooden_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 7),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:cobblestone", 8));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("minecraft:cobblestone", step.args().get("itemId"));
		assertEquals(1, step.args().get("quantity"));
		assertEquals(8, step.args().get("targetCount"));
	}

	@Test
	void recursivelyExpandsNeedsBeforeCurrentPrimitiveSteps() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.worldCropGroup("world-a", "minecraft:overworld", "farm-1", "minecraft:wheat"),
			Map.of("matureCount", 3, "totalCount", 9),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals(60, result.route().cost());
		ActionPlanStep harvest = result.route().steps().getFirst();
		assertEquals("harvest_wheat", harvest.stepId());
		assertEquals(List.of("minecraft:wheat"), harvest.args().get("blockIds"));
		assertEquals(3, harvest.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "obtain_wheat_then_craft");
		assertTrace(result.trace(), "route_selected", "obtain_wheat", "harvest_loaded_mature_wheat");
	}

	@Test
	void expectedFactsDoNotSatisfyGuards() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.EXPECTED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrace(result.trace(), "goal_failed", null, null);
	}

	@Test
	void staleFactsDoNotSatisfyGuards() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			100
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
	}

	@Test
	void returnsNoRouteWhenNoActionsetCanSatisfyGoal() {
		ActionResolveResult result = resolver(new ActionFactStore()).resolve(ActionGoal.inventoryItem("minecraft:netherite_ingot", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrue(result.route().steps().isEmpty());
		assertTrace(result.trace(), "goal_started", null, null);
		assertTrace(result.trace(), "goal_failed", null, null);
	}

	private static ActionResolver resolver(ActionFactStore facts) {
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(Path.of("actionsets"));
		assertTrue(load.valid(), () -> load.diagnostics().toString());
		return new ActionResolver(load.index(), facts, CONTEXT);
	}

	private static void addSurvivalCraftFacts(ActionFactStore facts) {
		for (CraftingOpportunity craft : ActionGraphRecipeFixtures.survivalCrafts()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.craftRecipe("world-a", "bot", craft.recipeId()),
				craftPayload(craft),
				ActionFactProvenance.INFERRED,
				90,
				ActionFact.NEVER_STALE
			));
		}
	}

	private static Map<String, Object> craftPayload(CraftingOpportunity craft) {
		return Map.of(
			"outputItemId", craft.outputItemId(),
			"outputCount", craft.outputCount(),
			"inputItemIds", craft.inputItemIds(),
			"inputCounts", inputCounts(craft.inputItemIds()),
			"gridKind", craft.gridKind().name()
		);
	}

	private static void addSurvivalSmeltFacts(ActionFactStore facts) {
		for (SmeltingRecipeKnowledge recipe : ActionGraphRecipeFixtures.survivalSmelts()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.smeltRecipe("world-a", "bot", recipe.optionId()),
				Map.of(
					"inputItemId", recipe.inputItemId(),
					"outputItemId", recipe.outputItemId(),
					"outputCount", recipe.outputCount(),
					"maxInputQuantity", recipe.maxInputQuantity(),
					"cookTimeTicks", recipe.cookTimeTicks(),
					"stationItemId", recipe.stationItemId(),
					"stationItemCount", recipe.stationItemCount()
				),
				ActionFactProvenance.INFERRED,
				90,
				ActionFact.NEVER_STALE
			));
		}
	}

	private static Map<String, Integer> inputCounts(List<String> itemIds) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		for (String itemId : itemIds) {
			counts.merge(itemId, 1, Integer::sum);
		}
		return Map.copyOf(counts);
	}

	private static void assertTrace(List<ActionTraceEvent> trace, String eventType, String actionId, String alternativeId) {
		long matches = trace.stream()
			.filter(event -> eventType.equals(event.eventType()))
			.filter(event -> actionId == null || actionId.equals(event.actionId()))
			.filter(event -> alternativeId == null || alternativeId.equals(event.alternativeId()))
			.count();
		assertTrue(matches > 0, () -> trace.toString());
	}
}
