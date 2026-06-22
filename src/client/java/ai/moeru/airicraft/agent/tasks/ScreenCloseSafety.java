package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.Airicraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

final class ScreenCloseSafety {
	private ScreenCloseSafety() {
	}

	static void closeHandledScreen(ClientPlayerEntity player, String context) {
		try {
			player.closeHandledScreen();
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Screen close hook failed during {}; continuing task cleanup", context, exception);
		}
	}

	static void clearScreen(MinecraftClient client, String context) {
		try {
			client.setScreen(null);
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Screen clear hook failed during {}; continuing task cleanup", context, exception);
		}
	}
}
