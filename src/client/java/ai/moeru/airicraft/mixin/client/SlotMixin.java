package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.client.CraftingResultSlotCraftEventBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Slot.class)
public class SlotMixin {
	@Inject(method = "onQuickTransfer", at = @At("HEAD"))
	private void airicraft$onQuickTransfer(ItemStack stack, ItemStack originalStack, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread()) {
			return;
		}
		if (!(((Object) this) instanceof CraftingResultSlot)) {
			return;
		}
		if (!(((Object) this) instanceof CraftingResultSlotCraftEventBridge craftingResultSlotBridge)) {
			return;
		}
		if (stack == null || originalStack == null || originalStack.isEmpty()) {
			return;
		}

		int craftedCount = originalStack.getCount() - stack.getCount();
		if (craftedCount <= 0) {
			return;
		}

		String itemId = Registries.ITEM.getId(originalStack.getItem()).toString();
		craftingResultSlotBridge.airicraft$markQuickTransferHandled();
		AiricraftClient.runtimeController().onPlayerCraftedItem(itemId, craftedCount);
	}
}
