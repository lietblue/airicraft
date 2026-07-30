package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingSlotSnapshot;
import ai.moeru.airicraft.agent.tasks.SmeltingStationCandidate;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKey;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKind;
import ai.moeru.airicraft.agent.tasks.SmeltingStationObservation;
import ai.moeru.airicraft.agent.tasks.SmeltingStationSource;
import ai.moeru.airicraft.agent.tasks.SmeltingStationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphPrimitiveMapperTest {
	@Test
	void mapsCraftItemOutputToAvailableRecipe() {
		ActionPlanStep step = primitive("craft_item", Map.of(
			"itemId", "minecraft:bread",
			"quantity", 2
		));

		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(step, List.of(
			new CraftingOpportunity(
				"wheat_wheat_wheat_to_bread",
				"minecraft:bread",
				1,
				List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"),
				CraftingGridKind.PLAYER_2X2
			)
		));

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.CRAFT_RECIPE, dispatch.proposal().type());
		assertEquals("wheat_wheat_wheat_to_bread", dispatch.proposal().craftRecipe().recipeId());
		assertEquals(2, dispatch.proposal().craftRecipe().times());
		assertEquals("craft_item", dispatch.selectedStep().targetId());
	}

	@Test
	void honorsRecipeIdWhenCraftStepProvidesOne() {
		ActionPlanStep step = primitive("craft_item", Map.of(
			"itemId", "minecraft:bread",
			"recipeId", "preferred_recipe",
			"quantity", 1
		));

		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(step, List.of(
			new CraftingOpportunity(
				"alternate_recipe",
				"minecraft:bread",
				1,
				List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat")
			),
			new CraftingOpportunity(
				"preferred_recipe",
				"minecraft:bread",
				1,
				List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat")
			)
		));

		assertTrue(dispatch.dispatchable());
		assertEquals("preferred_recipe", dispatch.proposal().craftRecipe().recipeId());
	}

	@Test
	void rejectsKnownRecipeWhenCurrentCraftSnapshotDoesNotContainIt() {
		ActionPlanStep step = primitive("craft_item", Map.of(
			"itemId", "minecraft:iron_pickaxe",
			"recipeId", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe",
			"quantity", 1
		));

		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(step, List.of());

		assertFalse(dispatch.dispatchable());
		assertEquals("recipe_not_found", dispatch.failureCode());
	}

	@Test
	void mapsCollectResourceToResourceJob() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("collect_resource", Map.of(
			"resourceKind", "WOOD_LOGS",
			"quantity", 3
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.COLLECT_RESOURCE, dispatch.proposal().type());
		assertEquals(3, dispatch.proposal().taskSpec().quantity());
		assertEquals("WOOD_LOGS", dispatch.proposal().taskSpec().resourceKind().name());
	}

	@Test
	void mapsMineBlockToInventoryAwareMineGoal() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("mine_block", Map.of(
			"blockIds", List.of("minecraft:wheat"),
			"quantity", 1,
			"targetCount", 3
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY, dispatch.proposal().type());
		assertEquals(List.of("minecraft:wheat"), dispatch.proposal().mineSpec().blockIds());
		assertEquals(3, dispatch.proposal().mineSpec().quantity());
		assertEquals(List.of("minecraft:wheat"), dispatch.proposal().mineSpec().matchingItemIds());
		assertEquals("ENSURE_BLOCKS_IN_INVENTORY", dispatch.payload().get("jobType"));
		assertEquals(1, dispatch.payload().get("quantity"));
		assertEquals(3, dispatch.payload().get("targetCount"));
	}

	@Test
	void mapsSmeltItemToSmeltingJob() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("smelt_item", Map.of(
			"itemId", "minecraft:iron_ingot",
			"optionId", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1",
			"inputQuantity", 3
		)), List.of(), List.of(smeltingOption()));

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.SMELT_ITEMS, dispatch.proposal().type());
		assertEquals("smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1", dispatch.proposal().smeltItems().optionId());
		assertEquals(3, dispatch.proposal().smeltItems().inputQuantity());
	}

	@Test
	void mapsSmeltItemFuelPlanToManualFuelSelection() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("smelt_item", Map.of(
			"itemId", "minecraft:iron_ingot",
			"optionId", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1",
			"inputQuantity", 3,
			"fuelItemId", "minecraft:birch_planks",
			"fuelQuantity", 2
		)), List.of(), List.of(smeltingOption()));

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.SMELT_ITEMS, dispatch.proposal().type());
		assertEquals("minecraft:birch_planks", dispatch.proposal().smeltItems().fuelItemId());
		assertEquals(2, dispatch.proposal().smeltItems().fuelQuantity());
		assertEquals("MANUAL", dispatch.proposal().smeltItems().fuelMode().name());
		assertEquals("minecraft:birch_planks", dispatch.payload().get("fuelItemId"));
		assertEquals(2, dispatch.payload().get("fuelQuantity"));
	}

	@Test
	void inferredSmeltStepBindsCurrentConcreteOption() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("smelt_item", Map.of(
			"itemId", "minecraft:iron_ingot",
			"inputItemId", "minecraft:raw_iron",
			"optionId", "inferred:minecraft_raw_iron_to_minecraft_iron_ingot",
			"inputQuantity", 3
		)), List.of(), List.of(smeltingOption()));

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.SMELT_ITEMS, dispatch.proposal().type());
		assertEquals("smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1", dispatch.proposal().smeltItems().optionId());
	}

	@Test
	void mapsCollectSmeltedItemToCollectionJob() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("collect_smelted_item", Map.of(
			"itemId", "minecraft:iron_ingot",
			"processId", "smelt-process-test"
		)), List.of(), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.COLLECT_SMELTED_ITEMS, dispatch.proposal().type());
		assertEquals("smelt-process-test", dispatch.proposal().collectSmeltedItems().processId());
		assertEquals("smelt-process-test", dispatch.payload().get("processId"));
	}

	@Test
	void mapsPathfindToNavigateGoal() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("pathfind_to", Map.of(
			"x", 1,
			"y", 64,
			"z", -2,
			"exactY", true
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.NAVIGATE_TO, dispatch.proposal().type());
		assertEquals(1, dispatch.proposal().position().x());
		assertEquals(64, dispatch.proposal().position().y());
		assertEquals(-2, dispatch.proposal().position().z());
		assertTrue(dispatch.proposal().position().exactY());
	}

	@Test
	void mapsTillSoilToUseBlockWithHoe() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("till_soil", Map.of(
			"itemId", "minecraft:stone_hoe",
			"x", -16,
			"y", 63,
			"z", -41
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.USE_BLOCK, dispatch.proposal().type());
		assertEquals("minecraft:stone_hoe", dispatch.proposal().blockUse().itemId());
		assertEquals(-16, dispatch.proposal().blockUse().targetPosition().x());
		assertEquals(63, dispatch.proposal().blockUse().targetPosition().y());
		assertEquals(-41, dispatch.proposal().blockUse().targetPosition().z());
		assertEquals("up", dispatch.proposal().blockUse().facePreference());
	}

	@Test
	void mapsPlantCropToUseBlockWithFarmlandGuard() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("plant_crop", Map.of(
			"itemId", "minecraft:wheat_seeds",
			"x", -16,
			"y", 64,
			"z", -41
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.USE_BLOCK, dispatch.proposal().type());
		assertEquals("minecraft:wheat_seeds", dispatch.proposal().blockUse().itemId());
		assertEquals(List.of("minecraft:farmland"), dispatch.proposal().blockUse().expectedSupportBlockIds());
		assertEquals("air", dispatch.proposal().blockUse().expectedTargetMaterial());
		assertEquals("down", dispatch.proposal().blockUse().facePreference());
	}

	@Test
	void mapsHydrateFarmlandToWaterBucketUseBlock() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("hydrate_farmland", Map.of(
			"x", -15,
			"y", 63,
			"z", -40
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.USE_BLOCK, dispatch.proposal().type());
		assertEquals("minecraft:water_bucket", dispatch.proposal().blockUse().itemId());
		assertEquals("air_or_replaceable", dispatch.proposal().blockUse().expectedTargetMaterial());
	}

	@Test
	void mapsClearFarmSiteToBreakBlocksWithExpectedIds() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("clear_farm_site", Map.of(
			"x", -15,
			"y", 63,
			"z", -40,
			"expectedBlockIds", List.of("minecraft:grass_block", "minecraft:dirt")
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.BREAK_BLOCKS, dispatch.proposal().type());
		assertEquals(-15, dispatch.proposal().blockBreak().targets().getFirst().position().x());
		assertEquals(List.of("minecraft:grass_block", "minecraft:dirt"), dispatch.proposal().blockBreak().targets().getFirst().expectedBlockIds());
	}

	@Test
	void rejectsCraftItemWhenNoMatchingRecipeIsAvailable() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("craft_item", Map.of(
			"itemId", "minecraft:bread",
			"quantity", 1
		)), List.of());

		assertFalse(dispatch.dispatchable());
		assertEquals("recipe_not_found", dispatch.failureCode());
	}

	private static ActionPlanStep primitive(String primitiveId, Map<String, Object> args) {
		return new ActionPlanStep(
			ActionStepKind.PRIMITIVE,
			"test_action",
			"test_alternative",
			"test_step",
			primitiveId,
			args
		);
	}

	private static SmeltingOption smeltingOption() {
		SmeltingStationKey key = new SmeltingStationKey("minecraft:overworld", 1, 64, 1);
		SmeltingSlotSnapshot slots = new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false);
		SmeltingStationCandidate candidate = new SmeltingStationCandidate(
			SmeltingStationSource.NEARBY_EXISTING,
			SmeltingStationState.EMPTY,
			SmeltingStationKind.FURNACE,
			key,
			2.0D,
			false
		);
		SmeltingStationObservation observation = new SmeltingStationObservation(key, SmeltingStationKind.FURNACE, slots, false, 2.0D);
		return new SmeltingOption(
			"smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			3,
			200,
			candidate,
			observation
		);
	}
}
