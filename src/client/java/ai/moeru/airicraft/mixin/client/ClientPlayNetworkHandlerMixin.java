package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onPlayerList", at = @At("TAIL"))
	private void airicraft$onPlayerList(PlayerListS2CPacket packet, CallbackInfo ci) {
		for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
			AiricraftClient.runtimeController().onPlayerJoinedGame(entry.profileId(), entry.profile().getName());
		}
	}

	@Inject(method = "onPlayerRemove", at = @At("TAIL"))
	private void airicraft$onPlayerRemove(PlayerRemoveS2CPacket packet, CallbackInfo ci) {
		for (java.util.UUID profileId : packet.profileIds()) {
			AiricraftClient.runtimeController().onPlayerLeftGame(profileId);
		}
	}
}
