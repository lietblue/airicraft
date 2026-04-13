package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.damage.DamageSource;

import java.util.UUID;

public final class ClientRuntimeController {
	private final AiricraftConfig config = AiricraftConfigLoader.load();
	private final HighlightManager highlightManager = new HighlightManager();
	private final FirstPersonScreenshotService screenshotService = new FirstPersonScreenshotService();
	private final EmbodiedAgentRuntime agentRuntime = EmbodiedAgentRuntime.createDefault(config, screenshotService);
	private final ModBridgeServer bridgeServer = new ModBridgeServer(highlightManager, agentRuntime, screenshotService);
	private final PlannerDebugOverlay plannerDebugOverlay = new PlannerDebugOverlay();

	public AiricraftConfig config() {
		return config;
	}

	public HighlightManager highlightManager() {
		return highlightManager;
	}

	public EmbodiedAgentRuntime agentRuntime() {
		return agentRuntime;
	}

	public PlannerDebugOverlayMode plannerDebugOverlayMode() {
		return plannerDebugOverlay.mode();
	}

	public boolean plannerDebugOverlayEnabled() {
		return plannerDebugOverlay.enabled();
	}

	public void setPlannerDebugOverlayMode(PlannerDebugOverlayMode mode) {
		plannerDebugOverlay.setMode(mode);
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

	public void onPlayerDamageObserved(DamageSource damageSource) {
		agentRuntime.onPlayerDamageObserved(damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		agentRuntime.onPlayerHealthUpdated(healthInitialized, healthBefore, healthAfter);
	}

	public void onPlayerRespawned() {
		agentRuntime.onPlayerRespawned();
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

	public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen != null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, agentRuntime, System.currentTimeMillis());
	}

	public void onScreenRender(DrawContext drawContext) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen == null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, agentRuntime, System.currentTimeMillis());
	}

	public boolean onScreenMouseScroll(double mouseX, double mouseY, double verticalAmount) {
		return plannerDebugOverlay.onMouseScroll(mouseX, mouseY, verticalAmount);
	}

	public void onFirstPersonFrameRendered() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			screenshotService.onWorldRendered(client);
		}
	}

	public void shutdown() {
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		plannerDebugOverlay.setMode(PlannerDebugOverlayMode.OFF);
		agentRuntime.shutdown();
		highlightManager.clear();
		bridgeServer.stop();
	}
}
