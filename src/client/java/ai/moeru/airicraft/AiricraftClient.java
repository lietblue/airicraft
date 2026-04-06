package ai.moeru.airicraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class AiricraftClient implements ClientModInitializer {
	private static final ClientRuntimeController RUNTIME_CONTROLLER = new ClientRuntimeController();

	public static ClientRuntimeController runtimeController() {
		return RUNTIME_CONTROLLER;
	}

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(RUNTIME_CONTROLLER::onClientStarted);
		ClientTickEvents.END_CLIENT_TICK.register(RUNTIME_CONTROLLER::onClientTick);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(RUNTIME_CONTROLLER::onWorldRender);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME_CONTROLLER.onWorldLeave());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RUNTIME_CONTROLLER.shutdown());
	}
}
