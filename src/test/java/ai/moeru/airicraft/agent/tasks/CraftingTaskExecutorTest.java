package ai.moeru.airicraft.agent.tasks;

import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.FALLBACK;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.OPEN_TABLE;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTaskExecutorTest {
	@Test
	void portableTableFromInventoryUsesFirstSafeNearbyGroundSite() {
		BlockPos origin = new BlockPos(39, 67, 152);
		BlockPos safeGroundSite = new BlockPos(39, 66, 153);

		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.PLACE_PORTABLE_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(false, true)
		);
		assertEquals(
			Optional.of(safeGroundSite),
			CraftingTaskExecutor.chooseCraftingTablePlacement(origin, safeGroundSite::equals)
		);
	}

	@Test
	void nearbyTableReuseWinsOverPortableTableSetup() {
		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.REUSE_NEARBY_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(true, true)
		);
	}

	@Test
	void nearbyTableSearchCanReturnFromAShallowMine() {
		assertTrue(CraftingTaskExecutor.craftingTableWithinSearchBounds(-8, 5, 5));
		assertFalse(CraftingTaskExecutor.craftingTableWithinSearchBounds(-17, 0, 0));
		assertFalse(CraftingTaskExecutor.craftingTableWithinSearchBounds(0, 9, 0));
	}

	@Test
	void portableTablePlacementRequiresWorldSafetyReachAndVisibility() {
		assertTrue(CraftingTaskExecutor.isSafeCraftingTablePlacement(true, true, true, true, true));
		assertFalse(CraftingTaskExecutor.isSafeCraftingTablePlacement(true, true, false, true, true));
		assertFalse(CraftingTaskExecutor.isSafeCraftingTablePlacement(true, true, true, false, true));
		assertFalse(CraftingTaskExecutor.isSafeCraftingTablePlacement(true, true, true, true, false));
	}

	@Test
	void portableCraftingTableSourceSlotsIncludeOffhand() {
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.INVENTORY_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.HOTBAR_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.OFFHAND_ID));

		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.CRAFTING_INPUT_START));
		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.EQUIPMENT_START));
	}

	@Test
	void stalledTableNavigationFallsBackAfterTimeout() {
		assertEquals(
			WAIT,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS
			)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS + 1
			)
		);
	}

	@Test
	void terminalNavigationEventsDoNotLoopForever() {
		assertEquals(
			OPEN_TABLE,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("AT_GOAL"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CANCELED"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CALC_FAILED"), false, false, 0)
		);
	}

	@Test
	void visibleScreensDoNotDetermineCraftingReadiness() {
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting(null));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("InventoryScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("ChatScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("GameMenuScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("GenericContainerScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("HandledScreen"));
	}

	@Test
	void staleContainerWithEmptyCursorIsClosedBeforeCrafting() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.CLOSE_OPEN_SCREEN,
			CraftingTaskExecutor.craftingScreenDisposition(false, false, true, true)
		);
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.CLOSE_OPEN_SCREEN,
			CraftingTaskExecutor.craftingScreenDisposition(false, true, false, true)
		);
	}

	@Test
	void craftingKeepsOnlyTheHandlerRequiredByItsGrid() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.READY,
			CraftingTaskExecutor.craftingScreenDisposition(true, false, false, true)
		);
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.READY,
			CraftingTaskExecutor.craftingScreenDisposition(false, true, true, true)
		);
	}

	@Test
	void staleContainerWithCarriedCursorItemFailsSafely() {
		assertEquals(
			CraftingTaskExecutor.CraftingScreenDisposition.FAIL,
			CraftingTaskExecutor.craftingScreenDisposition(false, false, true, false)
		);
	}

	@Test
	void craftPlanCarriesResolvedNetworkRecipeId() {
		NetworkRecipeId networkRecipeId = new NetworkRecipeId(42);
		CraftingOpportunityResolver.CraftingRecipeResolution resolution = new CraftingOpportunityResolver.CraftingRecipeResolution(
			networkRecipeId,
			null,
			4,
			2,
			CraftingGridKind.PLAYER_2X2,
			java.util.List.of(),
			null
		);

		CraftingTaskExecutor.CraftingPlan plan = CraftingTaskExecutor.toCraftingPlanForTests(resolution);

		assertEquals(networkRecipeId, plan.networkRecipeId());
		assertEquals(8, plan.targetOutputCount());
	}

	@Test
	void recipeFillRequestUsesHandlerSyncId() {
		NetworkRecipeId networkRecipeId = new NetworkRecipeId(7);

		CraftingTaskExecutor.RecipeFillRequest request = CraftingTaskExecutor.recipeFillRequestForTests(123, networkRecipeId);

		assertEquals(123, request.syncId());
		assertEquals(networkRecipeId, request.networkRecipeId());
		assertFalse(request.craftAll());
	}
}
