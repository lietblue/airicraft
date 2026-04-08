package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MessageHandler.class)
public class MessageHandlerMixin {
	@Inject(method = "onChatMessage", at = @At("TAIL"))
	private void airicraft$onChatMessage(
		SignedMessage message,
		GameProfile sender,
		MessageType.Parameters params,
		CallbackInfo ci
	) {
		if (sender == null) {
			return;
		}

		String content = message.getSignedContent();
		if (content == null || content.isBlank()) {
			Text fallback = message.getContent();
			content = fallback == null ? "" : fallback.getString();
		}

		if (content.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onChatReceived(sender.getName(), content);
	}

	@Inject(method = "onProfilelessMessage", at = @At("TAIL"))
	private void airicraft$onProfilelessMessage(Text content, MessageType.Parameters params, CallbackInfo ci) {
		String message = content == null ? "" : content.getString();
		if (message.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onSystemChatReceived(message);
	}

	@Inject(method = "onGameMessage", at = @At("TAIL"))
	private void airicraft$onGameMessage(Text content, boolean overlay, CallbackInfo ci) {
		if (overlay) {
			return;
		}

		String message = content == null ? "" : content.getString();
		if (message.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onSystemChatReceived(message);
	}
}
