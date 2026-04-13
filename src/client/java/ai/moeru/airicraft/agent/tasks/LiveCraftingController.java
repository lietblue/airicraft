package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class LiveCraftingController implements CraftingController {
	@Override
	public CraftingAttemptResult craft(String recipeId, boolean craftAll) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.interactionManager == null) {
			return CraftingAttemptResult.failed("minecraft_unavailable");
		}
		ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
		if (networkHandler == null) {
			return CraftingAttemptResult.failed("network_unavailable");
		}
		ScreenHandler handler = client.player.currentScreenHandler;
		Slot outputSlot;
		if (handler instanceof AbstractCraftingScreenHandler craftingHandler) {
			outputSlot = craftingHandler.getOutputSlot();
		} else if (handler instanceof PlayerScreenHandler) {
			outputSlot = handler.getSlot(0);
		} else {
			return CraftingAttemptResult.failed("crafting_screen_unavailable");
		}

		Optional<RecipeDisplayEntry> displayEntry = resolveDisplayEntry(networkHandler, recipeId);
		if (displayEntry.isEmpty()) {
			return CraftingAttemptResult.failed("recipe_not_found");
		}

		NetworkRecipeId networkRecipeId = displayEntry.get().id();
		client.interactionManager.clickRecipe(handler.syncId, networkRecipeId, craftAll);

		if (outputSlot == null || !outputSlot.hasStack()) {
			return CraftingAttemptResult.failed("recipe_not_craftable");
		}
		String outputItemId = Registries.ITEM.getId(outputSlot.getStack().getItem()).toString();
		client.interactionManager.clickSlot(handler.syncId, outputSlot.id, 0, SlotActionType.QUICK_MOVE, client.player);
		return CraftingAttemptResult.started(outputItemId);
	}

	private static Optional<RecipeDisplayEntry> resolveDisplayEntry(ClientPlayNetworkHandler networkHandler, String recipeId) {
		Identifier identifier = Identifier.tryParse(recipeId);
		if (identifier == null) {
			return Optional.empty();
		}
		RecipeManager recipeManager = networkHandler.getRecipeManager();
		if (!(recipeManager instanceof ServerRecipeManager serverRecipeManager)) {
			return Optional.empty();
		}
		RegistryKey<Recipe<?>> recipeKey = RegistryKey.of(RegistryKeys.RECIPE, identifier);
		AtomicReference<RecipeDisplayEntry> displayEntry = new AtomicReference<>();
		serverRecipeManager.forEachRecipeDisplay(recipeKey, entry -> {
			if (displayEntry.get() == null) {
				displayEntry.set(entry);
			}
		});
		return Optional.ofNullable(displayEntry.get());
	}
}
