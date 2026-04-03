package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onItemPickupAnimation", at = @At("HEAD"))
	private void airicraft$onItemPickupAnimation(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			return;
		}
		if (packet.getCollectorEntityId() != client.player.getId()) {
			return;
		}
		if (!(client.world.getEntityById(packet.getEntityId()) instanceof ItemEntity itemEntity)) {
			return;
		}

		ItemStack stack = itemEntity.getStack();
		if (stack == null || stack.isEmpty()) {
			return;
		}

		String itemId = Registries.ITEM.getId(stack.getItem()).toString();
		int count = Math.max(1, packet.getStackAmount());
		AiricraftClient.runtimeController().onPlayerPickedUpItem(itemId, count);
	}

	@Inject(method = "onPlayerList", at = @At("TAIL"))
	private void airicraft$onPlayerList(PlayerListS2CPacket packet, CallbackInfo ci) {
		for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
			AiricraftClient.runtimeController().onPlayerJoinedGame(entry.profileId(), entry.profile().name());
		}
	}

	@Inject(method = "onPlayerRemove", at = @At("TAIL"))
	private void airicraft$onPlayerRemove(PlayerRemoveS2CPacket packet, CallbackInfo ci) {
		for (java.util.UUID profileId : packet.profileIds()) {
			AiricraftClient.runtimeController().onPlayerLeftGame(profileId);
		}
	}
}
