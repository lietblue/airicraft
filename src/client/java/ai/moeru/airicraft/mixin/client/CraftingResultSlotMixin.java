package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.client.CraftingResultSlotCraftEventBridge;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.CraftingResultSlot;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingResultSlot.class)
public class CraftingResultSlotMixin implements CraftingResultSlotCraftEventBridge {
	@Unique
	private boolean airicraft$skipNextTakeItemCraftEvent;

	@Inject(method = "onTakeItem", at = @At("HEAD"))
	private void airicraft$onTakeItem(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
		if (airicraft$skipNextTakeItemCraftEvent) {
			airicraft$skipNextTakeItemCraftEvent = false;
			return;
		}
		if (player == null || stack == null || stack.isEmpty() || stack.getCount() <= 0) {
			return;
		}

		String itemId = Registries.ITEM.getId(stack.getItem()).toString();
		AiricraftClient.runtimeController().onPlayerCraftedItem(itemId, stack.getCount());
	}

	@Unique
	@Override
	public void airicraft$markQuickTransferHandled() {
		airicraft$skipNextTakeItemCraftEvent = true;
	}
}
