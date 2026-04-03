package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

public final class ClientRuntimeController {
	private final AiricraftConfig config = AiricraftConfigLoader.load();
	private final HighlightManager highlightManager = new HighlightManager();
	private final FirstPersonScreenshotService screenshotService = new FirstPersonScreenshotService();
	private final EmbodiedAgentRuntime agentRuntime = EmbodiedAgentRuntime.createDefault(config, screenshotService);
	private final ModBridgeServer bridgeServer = new ModBridgeServer(highlightManager, agentRuntime, screenshotService);

	public AiricraftConfig config() {
		return config;
	}

	public HighlightManager highlightManager() {
		return highlightManager;
	}

	public EmbodiedAgentRuntime agentRuntime() {
		return agentRuntime;
	}

	public FirstPersonScreenshotService screenshotService() {
		return screenshotService;
	}

	public void onClientStarted(MinecraftClient client) {
		agentRuntime.onClientStarted(client);
		bridgeServer.start();
	}

	public void onWorldLeave() {
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		agentRuntime.onWorldLeave();
		highlightManager.clear();
	}

	public void onClientTick(MinecraftClient client) {
		agentRuntime.onClientTick(client);
		highlightManager.tick();
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		agentRuntime.onChatReceived(senderName, plainTextMessage);
	}

	public void onSystemChatReceived(String plainTextMessage) {
		agentRuntime.onSystemChatReceived(plainTextMessage);
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		agentRuntime.onPlayerCraftedItem(itemId, count);
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		agentRuntime.onPlayerPickedUpItem(itemId, count);
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		agentRuntime.onPlayerJoinedGame(playerUuid, playerName);
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		agentRuntime.onPlayerLeftGame(playerUuid);
	}

	public void onWorldRender(WorldRenderContext context) {
		highlightManager.render(context);
	}

	public void onFirstPersonFrameRendered() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			screenshotService.onWorldRendered(client);
		}
	}

	public void shutdown() {
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		agentRuntime.shutdown();
		highlightManager.clear();
		bridgeServer.stop();
	}
}
