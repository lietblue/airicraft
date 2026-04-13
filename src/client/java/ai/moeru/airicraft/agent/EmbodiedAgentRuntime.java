package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.AiricraftConfigLoader;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.SingleplayerWorldService;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeRuntime;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeSnapshot;
import ai.moeru.airicraft.agent.chat.ChatService;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;
import ai.moeru.airicraft.agent.dialogue.DialogueSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.events.AgentEventPipeline;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyIntervention;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.follow.FollowCapability;
import ai.moeru.airicraft.agent.follow.FollowState;
import ai.moeru.airicraft.agent.goals.GoalDirector;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.session.LanHostingService;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.session.SessionRuntime;
import ai.moeru.airicraft.agent.social.ChatIngestService;
import ai.moeru.airicraft.agent.social.NearbyPlayerSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import ai.moeru.airicraft.agent.social.PrimaryInteractionPlayer;
import ai.moeru.airicraft.agent.social.PrimaryInteractionResolver;
import ai.moeru.airicraft.agent.verification.VerificationReport;
import ai.moeru.airicraft.agent.verification.VerificationRunner;
import ai.moeru.airicraft.agent.verification.VerificationPlayerProbe;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueChatSanitizationVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueClearGoalVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueProactiveSocialModeVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DamageFallContextVerification;
import ai.moeru.airicraft.agent.verification.scenarios.EventPolicyIgnoreSystemVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowSingleplayerLocalPauseVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowReacquireTargetVerification;
import ai.moeru.airicraft.agent.verification.scenarios.LlmDegradationVerification;
import ai.moeru.airicraft.agent.verification.scenarios.LlmDegradationGoalPreservedVerification;
import ai.moeru.airicraft.agent.verification.scenarios.ManualInputIdlePassthroughVerification;
import ai.moeru.airicraft.agent.verification.scenarios.PlannerObservabilityVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionLanVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialPrimaryInteractionTtlVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialChatIngestVerification;
import ai.moeru.airicraft.agent.session.SessionMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

public final class EmbodiedAgentRuntime {
	static final long CHAT_ECHO_SUPPRESSION_TICKS = 40L;
	private static final Map<String, EventRoutingProfile> EVENT_ROUTING_PROFILES = createEventRoutingProfiles();

	private final AiricraftConfig airicraftConfig;
	private final AgentConfig config;
	private final VerificationRunner verificationRunner = new VerificationRunner();
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final SessionRuntime sessionRuntime = new SessionRuntime();
	private final LanHostingService lanHostingService = new LanHostingService();
	private final SemanticEventBuffer eventBuffer = new SemanticEventBuffer(512);
	private final SemanticEventBuffer plannerEventBuffer = new SemanticEventBuffer(512);
	private final EventPolicyState eventPolicyState = new EventPolicyState();
	private final AgentEventPipeline eventPipeline = new AgentEventPipeline(eventBuffer, plannerEventBuffer, eventPolicyState, EVENT_ROUTING_PROFILES);
	private final ChatIngestService chatIngestService = new ChatIngestService();
	private final LocalDamageTracker localDamageTracker = new LocalDamageTracker();
	private final NearbyPlayerTracker nearbyPlayerTracker;
	private final PrimaryInteractionResolver primaryInteractionResolver = new PrimaryInteractionResolver(200L);
	private final GoalDirector goalDirector = new GoalDirector();
	private final FollowCapability followCapability = new FollowCapability();
	private final BehaviorTreeRuntime behaviorTreeRuntime = new BehaviorTreeRuntime();
	private final ChatService chatService = new ChatService();
	private final CurrentViewVisionService visionService;
	private final DialogueRuntime dialogueRuntime;

	private boolean initialized;
	private long tickCount;
	private long worldLoadTick = -1L;
	private Boolean proactiveSocialModeOverride;
	private SessionSnapshot sessionSnapshot = SessionSnapshot.initial();
	private FollowState followState = FollowState.idle();
	private long lastSystemChatTick = -1L;
	private String lastSystemChatText;
	private Float lastKnownPlayerHealth;
	private final Map<UUID, String> seenPlayerNames = new LinkedHashMap<>();

	public EmbodiedAgentRuntime(AiricraftConfig airicraftConfig, AgentConfig config, FirstPersonScreenshotService screenshotService) {
		this.airicraftConfig = Objects.requireNonNull(airicraftConfig, "airicraftConfig");
		this.config = Objects.requireNonNull(config, "config");
		this.nearbyPlayerTracker = new NearbyPlayerTracker(resolveNearbyPlayerTrackingRadius(airicraftConfig));
		this.visionService = new CurrentViewVisionService(
			Objects.requireNonNull(screenshotService, "screenshotService"),
			new OpenAiCompatibleVisionBackend(config.llm()),
			MinecraftClient::getInstance
		);
		Clock clock = Clock.systemDefaultZone();
		this.dialogueRuntime = new DialogueRuntime(
			new PlannerOrchestrator(
				new PlannerExecutor(new OpenAiCompatibleLlmBackend(config.llm())),
				new PlannerCompactionService(new OpenAiCompatibleChatClient(config.llm())),
				new PlannerContextAggregator(
					clock,
					config.llm().plannerCompactionTriggerTokens(),
					config.llm().plannerPendingSemanticEventCap(),
					config.llm().plannerVisionMode()
				),
				visionService,
				config.llm().plannerVisionMode(),
				config.llm().visionImageDetail(),
				config.llm().plannerSessionMaxConcurrentAttempts(),
				config.llm().plannerSessionCoalesceStepMillis(),
				config.llm().plannerSessionCoalesceMinMillis(),
				config.llm().plannerSessionCoalesceMaxMillis()
			),
			config.llm().maxRecentConversationTurns(),
			clock
		);
		registerDefaultScenarios();
	}

	public static EmbodiedAgentRuntime createDefault(AiricraftConfig airicraftConfig, FirstPersonScreenshotService screenshotService) {
		return new EmbodiedAgentRuntime(airicraftConfig, AgentConfigLoader.load(), screenshotService);
	}

	public static EmbodiedAgentRuntime createDefault(FirstPersonScreenshotService screenshotService) {
		return createDefault(AiricraftConfigLoader.load(), screenshotService);
	}

	public AgentConfig config() {
		return config;
	}

	public VerificationRunner verificationRunner() {
		return verificationRunner;
	}

	public List<String> verificationScenarioNames() {
		return verificationRunner.scenarioNames();
	}

	public void onClientStarted(MinecraftClient client) {
		initialized = true;
		sessionRuntime.onClientStarted(client, tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
	}

	public void onWorldLeave() {
		sessionRuntime.onWorldLeave(tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
		localDamageTracker.clear();
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		primaryInteractionResolver.clear();
		eventPolicyState.clear();
		eventPipeline.clearPlannerFeed();
		dialogueRuntime.clear();
		goalDirector.clear();
		followCapability.clear();
		followState = FollowState.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		seenPlayerNames.clear();
	}

	public void onClientTick(MinecraftClient client) {
		tickCount++;
		localDamageTracker.pruneStale(tickCount);
		FollowState previousFollowState = followState;
		BehaviorTreeSnapshot previousTreeSnapshot = behaviorTreeRuntime.snapshot();
		boolean wasWorldLoaded = sessionSnapshot.worldLoaded();
		sessionSnapshot = sessionRuntime.poll(client, tickCount, eventBuffer);
		if (!wasWorldLoaded && sessionSnapshot.worldLoaded()) {
			worldLoadTick = tickCount;
			localDamageTracker.onLifecycleReset(tickCount);
		}

		nearbyPlayerTracker.poll(client, tickCount, eventBuffer);
		primaryInteractionResolver.current().ifPresent(current ->
			primaryInteractionResolver.clearIfNotNearby(current.uuid(), nearbyPlayerTracker.isNearby(current.uuid()))
		);
		primaryInteractionResolver.expireInactive(tickCount);
		drainEventPipeline();

		DialogueResponse completedDialogueResponse = dialogueRuntime.poll(tickCount, eventBuffer);
		if (completedDialogueResponse != null) {
			Optional<GoalSnapshot> previousGoal = goalDirector.activeGoal();
			applyPlannerEventPolicyChanges(completedDialogueResponse.eventPolicyChanges());
			goalDirector.onPlannerResponse(completedDialogueResponse);
			recordPlannerOutcome(completedDialogueResponse, previousGoal, goalDirector.activeGoal());
			drainEventPipeline();
		}

		followState = followCapability.tick(
			client,
			sessionSnapshot,
			goalDirector.activeGoal(),
			nearbyPlayerTracker,
			tickCount,
			eventBuffer
		);
		behaviorTreeRuntime.tick(
			client,
			sessionSnapshot,
			dialogueRuntime,
			chatService,
			goalDirector.activeGoal(),
			followState,
			tickCount
		);
		if (previousFollowState.targetNearby() && !followState.targetNearby() && previousFollowState.targetPlayer() != null) {
			goalDirector.clearFollowGoal(previousFollowState.targetPlayer());
		}

		BehaviorTreeSnapshot currentTreeSnapshot = behaviorTreeRuntime.snapshot();
		if (
			followState.goalActive()
			&& followState.targetNearby()
			&& !previousTreeSnapshot.movement().stuck()
			&& currentTreeSnapshot.movement().stuck()
		) {
			eventBuffer.append(tickCount, "follow.stuck", Map.of(
				"player", followState.targetPlayer(),
				"distanceToTarget", followState.distanceToTarget()
			));
		}
		drainEventPipeline();

		verificationRunner.onTick();
		lastKnownPlayerHealth = currentPlayerHealth(client);
	}

	public void shutdown() {
		initialized = false;
		tickCount = 0L;
		worldLoadTick = -1L;
		verificationRunner.reset();
		localDamageTracker.clear();
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		eventPipeline.clear();
		primaryInteractionResolver.clear();
		dialogueRuntime.shutdown();
		visionService.shutdown();
		goalDirector.clear();
		followCapability.clear();
		followState = FollowState.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		seenPlayerNames.clear();
		sessionSnapshot = SessionSnapshot.initial();
	}

	public SessionSnapshot sessionSnapshot() {
		return sessionSnapshot.withTickCount(tickCount);
	}

	public AgentRuntimeSnapshot snapshot() {
		return new AgentRuntimeSnapshot(
			initialized,
			tickCount,
			sessionSnapshot(),
			verificationRunner.report()
		);
	}

	public VerificationReport verificationReport() {
		return verificationRunner.report();
	}

	public Optional<GoalSnapshot> activeGoal() {
		return goalDirector.activeGoal();
	}

	public BehaviorTreeSnapshot behaviorTreeSnapshot() {
		return behaviorTreeRuntime.snapshot();
	}

	public Optional<DialogueResponse> lastDialogueResponse() {
		return dialogueRuntime.lastResponse();
	}

	public DialogueSnapshot dialogueSnapshot() {
		return dialogueRuntime.snapshot();
	}

	public boolean llmAvailable() {
		return dialogueRuntime.llmAvailable();
	}

	public boolean visionAvailable() {
		return visionService.isConfigured();
	}

	public boolean isDegraded() {
		return dialogueRuntime.isDegraded();
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return dialogueRuntime.plannerDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return dialogueRuntime.plannerConversationDebugSnapshot();
	}

	public List<String> plannerContextExcerpt() {
		return dialogueRuntime.plannerContextExcerpt();
	}

	public int activeEventPolicyRuleCount() {
		return eventPolicyState.activeRuleCount();
	}

	public int recentEventPolicyInterventionCount() {
		return eventPolicyState.recentInterventionCount();
	}

	public EventPolicyDecision lastEventPolicyDecision() {
		return eventPolicyState.lastDecision().orElse(null);
	}

	public List<EventPolicyRule> activeEventPolicyRules() {
		return eventPolicyState.activeRules();
	}

	public List<EventPolicyIntervention> recentEventPolicyInterventions() {
		return eventPolicyState.recentInterventions();
	}

	public void clearEventPolicy() {
		eventPolicyState.clear();
	}

	public boolean verificationAvailable() {
		MinecraftClient client = MinecraftClient.getInstance();
		return sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL
			&& sessionSnapshot.worldLoaded()
			&& client != null
			&& client.player != null
			&& client.isIntegratedServerRunning()
			&& client.getServer() != null;
	}

	public long latestEventSeqNo() {
		return eventBuffer.latestSeqNo();
	}

	public VerificationPlayerProbe verificationPlayerProbe() {
		prepareClientForVerification();
		return onVerificationServer((server, player) -> verificationPlayerProbe(player));
	}

	public VerificationPlayerProbe verificationTeleportPlayer(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
		}
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			player.requestTeleport(x, y, z);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationSetPlayerVelocity(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
		}
		prepareClientForVerification();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		client.player.setVelocityClient(new Vec3d(x, y, z));
		client.player.setOnGround(false);
		return onVerificationServer((server, player) -> {
			player.setVelocity(x, y, z);
			player.velocityDirty = true;
			player.setOnGround(false);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationSetGameMode(String modeId) {
		GameMode gameMode = verificationGameMode(modeId);
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			player.changeGameMode(gameMode);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationRunCommand(String command) {
		String normalizedCommand = normalizedVerificationCommand(command);
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			server.getCommandManager().parseAndExecute(
				server.getCommandSource()
					.withEntity(player)
					.withPosition(new Vec3d(player.getX(), player.getY(), player.getZ()))
					.withWorld(player.getEntityWorld())
					.withSilent(),
				normalizedCommand
			);
			return verificationPlayerProbe(player);
		});
	}

	public boolean verificationRequestRespawn() {
		prepareClientForVerification();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		client.player.requestRespawn();
		return true;
	}

	public boolean startDebugCompaction() {
		return dialogueRuntime.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return dialogueRuntime.pollDebugCompaction();
	}

	public long lastChatTick() {
		return chatService.lastChatTick();
	}

	public String lastChatText() {
		return chatService.lastChatText();
	}

	public boolean startVerification(String scenarioName) {
		proactiveSocialModeOverride = null;
		prepareClientForVerification();
		return verificationRunner.start(scenarioName);
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		if (isAgentChatEcho(
			senderName,
			plainTextMessage,
			localPlayerName(),
			chatService.lastChatText(),
			tickCount,
			chatService.lastChatTick()
		)) {
			return;
		}
		if (isLocalControllerMessage(senderName, localPlayerName())) {
			eventBuffer.append(tickCount, "social.local_controller_spoke", Map.of(
				"player", senderName,
				"message", plainTextMessage,
				"normalizedMessage", ChatIngestService.normalize(plainTextMessage)
			));

			String plannerSender = DialogueSpeakerLabels.SAME_CLIENT_ADMIN;
			if (dialogueRuntime.handleResetCommand(plannerSender, plainTextMessage, tickCount, eventBuffer)) {
				eventPolicyState.clear();
				drainEventPipeline();
				return;
			}
			drainEventPipeline();
			return;
		}

		chatIngestService.ingest(
			senderName,
			plainTextMessage,
			tickCount,
			nearbyPlayerTracker,
			primaryInteractionResolver,
			eventBuffer
		);

		if (dialogueRuntime.handleResetCommand(senderName, plainTextMessage, tickCount, eventBuffer)) {
			eventPolicyState.clear();
			drainEventPipeline();
			return;
		}
		drainEventPipeline();
	}

	public void onSystemChatReceived(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "crafting.item_crafted", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "pickup.item_picked_up", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
	}

	public void onPlayerDamageObserved(DamageSource damageSource) {
		localDamageTracker.observeDamageSource(tickCount, damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		float effectiveHealthBefore = resolveEffectiveHealthBefore(healthBefore, healthAfter);
		Map<String, Object> payload = localDamageTracker.consumeDamage(healthInitialized, tickCount, effectiveHealthBefore, healthAfter);
		lastKnownPlayerHealth = healthAfter;
		if (payload == null) {
			return;
		}

		eventBuffer.append(tickCount, "combat.damage_taken", payload);
		drainEventPipeline();
	}

	public void onPlayerRespawned() {
		localDamageTracker.onLifecycleReset(tickCount);
		lastKnownPlayerHealth = null;
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		if (playerUuid == null || playerName == null || playerName.isBlank()) {
			return;
		}
		if (isLocalPlayer(playerUuid, playerName)) {
			seenPlayerNames.put(playerUuid, playerName);
			return;
		}
		if (seenPlayerNames.putIfAbsent(playerUuid, playerName) != null) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_joined_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " joined the game");
		drainEventPipeline();
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		if (playerUuid == null) {
			return;
		}

		String playerName = seenPlayerNames.remove(playerUuid);
		if (playerName == null || playerName.isBlank() || isLocalPlayer(playerUuid, playerName)) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_left_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " left the game");
		drainEventPipeline();
	}

	public SemanticEventQueryResult recentEvents(Long sinceSeqNo) {
		return eventBuffer.query(sinceSeqNo);
	}

	public Optional<PrimaryInteractionPlayer> primaryInteractionPlayer() {
		return primaryInteractionResolver.current();
	}

	public List<NearbyPlayerSnapshot> nearbyPlayers() {
		return nearbyPlayerTracker.snapshot();
	}

	public Map<String, Object> openLan() {
		return lanHostingService.openLan(sessionSnapshot);
	}

	public void injectMockPlannerResponse(PlannerResponse response) {
		dialogueRuntime.injectMockResponse(response);
	}

	public void injectPlannerTimeout() {
		dialogueRuntime.injectTimeout();
	}

	public VisionDescription describeCapturedView(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		return visionService.describe(screenshot, prompt);
	}

	private boolean proactiveSocialModeEnabled() {
		return proactiveSocialModeOverride != null
			? proactiveSocialModeOverride.booleanValue()
			: airicraftConfig.enableProactiveSocialMode();
	}

	private void setProactiveSocialModeOverride(Boolean enabled) {
		proactiveSocialModeOverride = enabled;
	}

	private boolean playerChatWithinConfiguredDistance(String senderName) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return true;
		}

		Optional<NearbyPlayerSnapshot> nearbyPlayer = nearbyPlayerTracker.findByName(senderName);
		if (nearbyPlayer.isEmpty()) {
			return false;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return false;
		}

		Vec3d selfPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		Vec3d senderPos = new Vec3d(nearbyPlayer.get().x(), nearbyPlayer.get().y(), nearbyPlayer.get().z());
		double maxDistance = airicraftConfig.socialChatMaxDistanceBlocks();
		return selfPos.squaredDistanceTo(senderPos) <= maxDistance * maxDistance;
	}

	private static double resolveNearbyPlayerTrackingRadius(AiricraftConfig airicraftConfig) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return 32.0D;
		}
		return Math.max(32.0D, airicraftConfig.socialChatMaxDistanceBlocks());
	}

	private String localPlayerName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return null;
		}
		Text playerName = client.player.getName();
		return playerName == null ? null : playerName.getString();
	}

	static boolean isAgentChatEcho(
		String senderName,
		String plainTextMessage,
		String localPlayerName,
		String lastAgentChatText,
		long currentTick,
		long lastAgentChatTick
	) {
		if (senderName == null || plainTextMessage == null || localPlayerName == null || lastAgentChatText == null) {
			return false;
		}
		if (!senderName.equals(localPlayerName)) {
			return false;
		}
		if (!plainTextMessage.equals(lastAgentChatText)) {
			return false;
		}
		if (lastAgentChatTick < 0L || currentTick < lastAgentChatTick) {
			return false;
		}
		return currentTick - lastAgentChatTick <= CHAT_ECHO_SUPPRESSION_TICKS;
	}

	static boolean isLocalControllerMessage(String senderName, String localPlayerName) {
		if (senderName == null || localPlayerName == null) {
			return false;
		}
		return senderName.equals(localPlayerName);
	}

	private boolean isDuplicateSystemChat(String plainTextMessage, long currentTick) {
		if (plainTextMessage == null || plainTextMessage.isBlank()) {
			return true;
		}
		boolean duplicate = currentTick == lastSystemChatTick && plainTextMessage.equals(lastSystemChatText);
		lastSystemChatTick = currentTick;
		lastSystemChatText = plainTextMessage;
		return duplicate;
	}

	private void forwardSyntheticPresenceMessage(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	private boolean isLocalPlayer(UUID playerUuid, String playerName) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return false;
		}
		if (client.player != null && playerUuid.equals(client.player.getUuid())) {
			return true;
		}
		return client.getSession() != null && playerName.equals(client.getSession().getUsername());
	}

	private void recordPlannerOutcome(
		DialogueResponse response,
		Optional<GoalSnapshot> previousGoal,
		Optional<GoalSnapshot> currentGoal
	) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("intentType", response.intent().type().name());
		if (response.intent().targetPlayer() != null && !response.intent().targetPlayer().isBlank()) {
			payload.put("targetPlayer", response.intent().targetPlayer());
		}
		if (response.intent().goalType() != null) {
			payload.put("goalType", response.intent().goalType().name());
		}
		if (response.text() != null && !response.text().isBlank()) {
			payload.put("replyText", response.text());
		}
		eventBuffer.append(tickCount, "planner.response_applied", payload);

		if (response.intent().type() == DialogueIntentType.SET_GOAL && currentGoal.isPresent()) {
			eventBuffer.append(tickCount, "planner.goal_set", Map.of(
				"goalType", currentGoal.get().type().name(),
				"targetPlayer", currentGoal.get().targetPlayer(),
				"source", currentGoal.get().source()
			));
			return;
		}

		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL && previousGoal.isPresent() && currentGoal.isEmpty()) {
			eventBuffer.append(tickCount, "planner.goal_cleared", Map.of(
				"goalType", previousGoal.get().type().name(),
				"targetPlayer", previousGoal.get().targetPlayer(),
				"source", previousGoal.get().source()
			));
		}
	}

	private void drainEventPipeline() {
		List<ai.moeru.airicraft.agent.llm.PlannerTrigger> triggers = eventPipeline.drain(this::createPlannerTrigger);
		if (triggers.isEmpty()) {
			return;
		}
		String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
		Optional<GoalSnapshot> activeGoal = goalDirector.activeGoal();
		for (ai.moeru.airicraft.agent.llm.PlannerTrigger trigger : triggers) {
			dialogueRuntime.onPlannerTrigger(
				trigger,
				sessionSnapshot,
				primaryInteractionPlayer,
				activeGoal,
				plannerEventBuffer
			);
		}
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlannerTrigger(SemanticEvent event, EventRoutingProfile profile) {
		String eventType = event.type();
		if (eventType == null) {
			return null;
		}
		return switch (eventType) {
			case "social.player_spoke" -> createPlayerSpokeTrigger(event);
			case "social.player_addressed_agent" -> createAddressedChatTrigger(event);
			case "social.local_controller_spoke" -> createLocalControllerTrigger(event);
			case "social.system_message" -> createSystemTrigger(event);
			case "pickup.item_picked_up" -> createPickupTrigger(event);
			case "crafting.item_crafted" -> createCraftTrigger(event);
			case "combat.damage_taken" -> createDamageTrigger(event);
			default -> null;
		};
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlayerSpokeTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (ChatIngestService.isAddressedToAgent(message)) {
			return null;
		}
		if (!proactiveSocialModeEnabled() || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createAddressedChatTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (DialogueRuntime.isResetCommand(message) || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createLocalControllerTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || DialogueRuntime.isResetCommand(message)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.CHAT,
			DialogueSpeakerLabels.SAME_CLIENT_ADMIN,
			message,
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createSystemTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || !proactiveSocialModeEnabled()) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "server", message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPickupTrigger(SemanticEvent event) {
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.PICKUP,
			"self",
			"Picked up " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createCraftTrigger(SemanticEvent event) {
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.CRAFT,
			"self",
			"I crafted " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createDamageTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String damageTypeId = stringPayloadValue(payload, "damageTypeId");
		String attackerName = stringPayloadValue(payload, "attackerName");
		Float amount = floatPayloadValue(payload, "amount");
		Float resultingHealth = floatPayloadValue(payload, "healthAfter");
		if (amount == null && resultingHealth == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("I took ")
			.append(formatDecimal(amount == null ? 0.0F : amount))
			.append(" damage");
		if (attackerName != null) {
			message.append(" from ").append(attackerName);
		}
		else if (damageTypeId != null) {
			message.append(" from ").append(damageTypeId);
		}
		if (resultingHealth != null) {
			message.append(" and dropped to ").append(formatDecimal(resultingHealth)).append(" health");
		}
		message.append('.');
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.DAMAGE,
			"self",
			message.toString(),
			event.tick(),
			event.timestampMs()
		);
	}

	private void applyPlannerEventPolicyChanges(EventPolicyChanges changes) {
		if (changes == null) {
			return;
		}
		long timestampMs = System.currentTimeMillis();
		if (changes.clearAll()) {
			eventPolicyState.clear();
		}
		eventPolicyState.removeRuleIds(changes.removeRuleIds());
		for (EventPolicyRuleUpsert upsert : changes.upserts()) {
			applyPlannerEventPolicyUpsert(upsert, timestampMs);
		}
	}

	private void applyPlannerEventPolicyUpsert(EventPolicyRuleUpsert upsert, long timestampMs) {
		if (upsert == null) {
			return;
		}
		EventPolicyMatch match = upsert.match();
		String eventType = match == null ? null : match.eventType();
		EventPolicyEffect effect = EventPolicyEffect.parse(upsert.effect());
		String ruleId = normalizeRuleId(upsert.ruleId());
		if (ruleId == null) {
			ruleId = "planner-rule-" + timestampMs + "-" + eventPolicyState.activeRuleCount();
		}
		if (match == null || !match.isValid()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "eventType is required");
			return;
		}
		EventRoutingProfile profile = EVENT_ROUTING_PROFILES.get(eventType);
		if (profile != null && profile.policyBypass()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "event type bypasses planner-authored policy");
			return;
		}
		if (effect == null) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "effect must be allow, ignore, semantic_only, or trigger_only");
			return;
		}
		eventPolicyState.upsert(new EventPolicyRule(
			ruleId,
			effect,
			match,
			upsert.reason(),
			timestampMs,
			null,
			0L,
			"planner"
		));
	}

	private void recordPolicyRuleRejected(String ruleId, String eventType, String effect, String reason) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (ruleId != null) {
			payload.put("ruleId", ruleId);
		}
		if (eventType != null) {
			payload.put("eventType", eventType);
		}
		if (effect != null && !effect.isBlank()) {
			payload.put("effect", effect);
		}
		payload.put("reason", reason == null || reason.isBlank() ? "rule rejected" : reason);
		eventBuffer.append(tickCount, "policy.rule_rejected", payload);
	}

	private static String normalizeRuleId(String ruleId) {
		if (ruleId == null) {
			return null;
		}
		String trimmed = ruleId.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Map<String, EventRoutingProfile> createEventRoutingProfiles() {
		LinkedHashMap<String, EventRoutingProfile> profiles = new LinkedHashMap<>();
		profiles.put("social.player_spoke", new EventRoutingProfile("social.player_spoke", false, PlannerTriggerType.CHAT, false));
		profiles.put("social.player_addressed_agent", new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.local_controller_spoke", new EventRoutingProfile("social.local_controller_spoke", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false));
		profiles.put("pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false));
		profiles.put("crafting.item_crafted", new EventRoutingProfile("crafting.item_crafted", true, PlannerTriggerType.CRAFT, false));
		profiles.put("combat.damage_taken", new EventRoutingProfile("combat.damage_taken", true, PlannerTriggerType.DAMAGE, false));
		profiles.put("session.world_loaded", new EventRoutingProfile("session.world_loaded", true, null, false));
		profiles.put("session.world_unloaded", new EventRoutingProfile("session.world_unloaded", true, null, false));
		profiles.put("session.connection_lost", new EventRoutingProfile("session.connection_lost", true, null, false));
		profiles.put("session.lan_opened", new EventRoutingProfile("session.lan_opened", true, null, false));
		profiles.put("social.player_joined_game", new EventRoutingProfile("social.player_joined_game", true, null, false));
		profiles.put("social.player_left_game", new EventRoutingProfile("social.player_left_game", true, null, false));
		profiles.put("social.player_joined_nearby", new EventRoutingProfile("social.player_joined_nearby", true, null, false));
		profiles.put("social.player_left_nearby", new EventRoutingProfile("social.player_left_nearby", true, null, false));
		profiles.put("follow.target_acquired", new EventRoutingProfile("follow.target_acquired", true, null, false));
		profiles.put("follow.target_lost", new EventRoutingProfile("follow.target_lost", true, null, false));
		profiles.put("follow.stuck", new EventRoutingProfile("follow.stuck", true, null, false));
		profiles.put("planner.goal_set", new EventRoutingProfile("planner.goal_set", true, null, false));
		profiles.put("planner.goal_cleared", new EventRoutingProfile("planner.goal_cleared", true, null, false));
		profiles.put("planner.degraded_entered", new EventRoutingProfile("planner.degraded_entered", true, null, false));
		profiles.put("planner.degraded_cleared", new EventRoutingProfile("planner.degraded_cleared", true, null, false));
		profiles.put("planner.reset_requested", new EventRoutingProfile("planner.reset_requested", true, null, true));
		profiles.put("policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened"));
		profiles.put("policy.rule_rejected", EventRoutingProfile.rawOnly("policy.rule_rejected"));
		return Map.copyOf(profiles);
	}

	private void registerDefaultScenarios() {
		verificationRunner.register(new SessionVerification(
			() -> sessionSnapshot.mode(),
			this::joinFirstWorld,
			this::leaveCurrentWorld,
			() -> eventBuffer.containsType("session.world_loaded"),
			() -> worldLoadTick >= 0L && tickCount - worldLoadTick >= 20L
		));
		verificationRunner.register(new SessionLanVerification(
			() -> sessionSnapshot.mode(),
			this::openLan,
			() -> sessionSnapshot.lanPort() > 0,
			() -> eventBuffer.containsType("session.lan_opened")
		));
		verificationRunner.register(new SocialChatIngestVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("Alice", new Vec3d(5.0D, 64.0D, 0.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("Alice", "hello everyone", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> chatIngestService.injectMessage("Alice", "@agent follow me", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> eventBuffer.containsTypeForPlayer("social.player_joined_nearby", "Alice"),
			() -> eventBuffer.containsTypeForPlayer("social.player_spoke", "Alice"),
			() -> eventBuffer.containsTypeForPlayer("social.player_addressed_agent", "Alice"),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("Alice"::equals).isPresent()
		));
		verificationRunner.register(new FollowSingleplayerLocalPauseVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"I'll follow once LAN or multiplayer is active.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "PausedAlice")
			)),
			() -> nearbyPlayerTracker.injectPlayerNearby("PausedAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> onChatReceived("PausedAlice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse()
				.map(response -> response.intent().type() == DialogueIntentType.SET_GOAL && "PausedAlice".equals(response.intent().targetPlayer()))
				.orElse(false),
			() -> eventBuffer.containsTypeForPlayer("follow.target_acquired", "PausedAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "PausedAlice".equals(goal.targetPlayer())).orElse(false),
			() -> behaviorTreeSnapshot().activeNodePath().contains("ActuationBlockedBySession"),
			() -> !behaviorTreeSnapshot().movement().movingForward()
				&& !behaviorTreeSnapshot().movement().sprinting()
				&& !behaviorTreeSnapshot().movement().jumping()
		));
		verificationRunner.register(new ManualInputIdlePassthroughVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> activeGoal().isEmpty(),
			() -> setForwardKeyPressed(true),
			this::isForwardKeyPressed,
			() -> setForwardKeyPressed(false)
		));
		verificationRunner.register(new FollowVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"I'll follow once LAN is open.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "LanAlice")
			)),
			() -> nearbyPlayerTracker.injectPlayerNearby("LanAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> onChatReceived("LanAlice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse()
				.map(response -> response.intent().type() == DialogueIntentType.SET_GOAL && "LanAlice".equals(response.intent().targetPlayer()))
				.orElse(false),
			() -> eventBuffer.containsTypeForPlayer("follow.target_acquired", "LanAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "LanAlice".equals(goal.targetPlayer())).orElse(false),
			this::openLan,
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LAN_HOST,
			() -> nearbyPlayerTracker.injectPlayerMove("LanAlice", playerOffset(20.0D), tickCount, eventBuffer),
			() -> behaviorTreeSnapshot().activeNodePath().stream().anyMatch(node -> node.contains("MoveCloser")),
			() -> nearbyPlayerTracker.injectPlayerDisconnect("LanAlice", tickCount, eventBuffer),
			() -> eventBuffer.containsTypeForPlayer("follow.target_lost", "LanAlice")
		));
		verificationRunner.register(new DialogueVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("Alice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Sure, I'll follow you!",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
			)),
			() -> onChatReceived("Alice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse().map(response -> response.text() != null && !response.text().isBlank()).orElse(false),
			() -> lastChatTick() > 0L,
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER).orElse(false)
		));
		verificationRunner.register(new DialogueChatSanitizationVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("SanitizeAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> lastChatTick(),
			this::lastChatText,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"/follow me\n\n§a".repeat(40),
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("SanitizeAlice", "@agent say something")
		));
		verificationRunner.register(new DialogueClearGoalVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ClearGoalAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ClearGoalAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ClearGoalAlice")
			)),
			() -> onChatReceived("ClearGoalAlice", "@agent follow me"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "ClearGoalAlice".equals(goal.targetPlayer())).orElse(false),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Stopping.",
				new PlannerIntent("clear_goal", null, null)
			)),
			() -> onChatReceived("ClearGoalAlice", "@agent stop following"),
			() -> activeGoal().isEmpty(),
			() -> !behaviorTreeSnapshot().activeNodePath().contains("FollowPlayerSubtree"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_set"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_cleared")
		));
		verificationRunner.register(new SocialPrimaryInteractionTtlVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("TtlAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("TtlAlice", "hello", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("TtlAlice"::equals).isPresent(),
			() -> primaryInteractionResolver.current().isEmpty(),
			() -> nearbyPlayerTracker.injectPlayerNearby("TtlBob", playerOffset(6.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("TtlBob", "hey there", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("TtlBob"::equals).isPresent()
		));
		verificationRunner.register(new DialogueProactiveSocialModeVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ProactiveAlice", playerOffset(5.0D), tickCount, eventBuffer),
			enabled -> setProactiveSocialModeOverride(enabled),
			() -> lastDialogueResponse().map(DialogueResponse::tick).orElse(-1L),
			() -> tickCount,
			() -> onChatReceived("ProactiveAlice", "hello there"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Hi ProactiveAlice.",
				new PlannerIntent("reply_only", null, null)
			))
		));
		verificationRunner.register(new LlmDegradationVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> injectPlannerTimeout(),
			() -> injectPlannerTimeout(),
			() -> injectPlannerTimeout(),
			() -> isDegraded(),
			() -> behaviorTreeSnapshot().activeNodePath() != null && !behaviorTreeSnapshot().activeNodePath().isEmpty(),
			() -> eventBuffer.containsType("planner.degraded_entered"),
			() -> lastChatTick() > 0L,
			() -> onChatReceived("Alice", "@agent reset"),
			() -> !isDegraded(),
			() -> eventBuffer.containsType("planner.degraded_cleared"),
			() -> eventBuffer.containsType("planner.reset_requested"),
			() -> "Planner state reset.".equals(lastChatText())
		));
		verificationRunner.register(new LlmDegradationGoalPreservedVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("DegradedAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following DegradedAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "DegradedAlice")
			)),
			() -> onChatReceived("DegradedAlice", "@agent follow me"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "DegradedAlice".equals(goal.targetPlayer())).orElse(false),
			this::injectPlannerTimeout,
			this::injectPlannerTimeout,
			this::injectPlannerTimeout,
			this::isDegraded,
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "DegradedAlice".equals(goal.targetPlayer())).orElse(false),
			() -> behaviorTreeSnapshot().activeNodePath() != null && !behaviorTreeSnapshot().activeNodePath().isEmpty(),
			() -> onChatReceived("DegradedAlice", "@agent reset"),
			() -> !isDegraded()
		));
		verificationRunner.register(new FollowReacquireTargetVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ReacquireAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ReacquireAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ReacquireAlice")
			)),
			() -> onChatReceived("ReacquireAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_acquired", "ReacquireAlice"),
			() -> nearbyPlayerTracker.injectPlayerDisconnect("ReacquireAlice", tickCount, eventBuffer),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_lost", "ReacquireAlice"),
			() -> activeGoal().isEmpty(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ReacquireAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ReacquireAlice again.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ReacquireAlice")
			)),
			() -> onChatReceived("ReacquireAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_acquired", "ReacquireAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "ReacquireAlice".equals(goal.targetPlayer())).orElse(false)
		));
		verificationRunner.register(new DamageFallContextVerification(
			this::verificationAvailable,
			this::verificationPlayerProbe,
			() -> verificationSetGameMode("survival"),
			() -> verificationRunCommand("effect give @s resistance 10 3 true"),
			(x, y, z) -> verificationSetPlayerVelocity(x, y, z),
			this::latestEventSeqNo,
			sinceSeqNo -> recentEvents(sinceSeqNo),
			this::plannerContextExcerpt
		));
		verificationRunner.register(new PlannerObservabilityVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ObserveAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Hi ObserveAlice.",
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("ObserveAlice", "@agent hi"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.response_applied"),
			() -> lastDialogueResponse().map(response -> response.intent().type() == DialogueIntentType.REPLY_ONLY).orElse(false),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ObserveAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ObserveAlice")
			)),
			() -> onChatReceived("ObserveAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_set"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Stopping.",
				new PlannerIntent("clear_goal", null, null)
			)),
			() -> onChatReceived("ObserveAlice", "@agent stop"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_cleared")
		));
		verificationRunner.register(new EventPolicyIgnoreSystemVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("PolicyAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Okay, I'll ignore repeated system messages for now.",
				new PlannerIntent("reply_only", null, null),
				null,
				new EventPolicyChanges(
					false,
					List.of(),
					List.of(new EventPolicyRuleUpsert(
						"mute-system-server",
						"ignore",
						new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
						"Ignore repeated server system chatter for this session."
					))
				)
			)),
			() -> onChatReceived("PolicyAlice", "@agent ignore repeated server system messages"),
			this::activeEventPolicyRules,
			this::recentEventPolicyInterventions,
			() -> onSystemChatReceived("Policy harness system noise"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Bypass chat still works.",
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("PolicyAlice", "@agent say hi again"),
			this::latestEventSeqNo,
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.response_applied"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "social.system_message"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "policy.event_intervened")
		));
	}

	private static String stringPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	private static Float floatPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Float.parseFloat(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String formatDecimal(float value) {
		if (Math.abs(value - Math.round(value)) < 0.001F) {
			return Integer.toString(Math.round(value));
		}
		String text = String.format(java.util.Locale.ROOT, "%.2f", value);
		int trimIndex = text.length();
		while (trimIndex > 0 && text.charAt(trimIndex - 1) == '0') {
			trimIndex--;
		}
		if (trimIndex > 0 && text.charAt(trimIndex - 1) == '.') {
			trimIndex--;
		}
		return text.substring(0, trimIndex);
	}

	private void ensureVerificationSessionAvailable() {
		if (sessionSnapshot.mode() != SessionMode.SINGLEPLAYER_LOCAL) {
			throw new BridgeUnavailableException("unsupported_session_state", "Verification actions require a singleplayer local world");
		}
		if (!sessionSnapshot.worldLoaded()) {
			throw new BridgeUnavailableException("verification_unavailable", "No singleplayer local world is loaded for verification");
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || !client.isIntegratedServerRunning() || client.getServer() == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Integrated singleplayer verification controls are unavailable");
		}
	}

	private void prepareClientForVerification() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		if (client.options != null && client.options.pauseOnLostFocus) {
			client.options.pauseOnLostFocus = false;
			client.options.write();
		}
		if (client.currentScreen != null && "GameMenuScreen".equals(client.currentScreen.getClass().getSimpleName())) {
			client.setScreen(null);
		}
	}

	private <T> T onVerificationServer(BiFunction<IntegratedServer, ServerPlayerEntity, T> action) {
		ensureVerificationSessionAvailable();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		IntegratedServer server = client.getServer();
		if (server == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Integrated server is unavailable");
		}
		UUID playerUuid = client.player.getUuid();
		CompletableFuture<T> future = new CompletableFuture<>();
		server.executeSync(() -> {
			try {
				ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
				if (serverPlayer == null) {
					throw new BridgeUnavailableException("verification_unavailable", "Server-side verification player is unavailable");
				}
				future.complete(action.apply(server, serverPlayer));
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get(5L, TimeUnit.SECONDS);
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Verification action failed on integrated server thread", exception.getCause());
		}
		catch (Exception exception) {
			throw new BridgeUnavailableException("verification_unavailable", "Timed out waiting for integrated server verification action");
		}
	}

	private static VerificationPlayerProbe verificationPlayerProbe(ServerPlayerEntity player) {
		return new VerificationPlayerProbe(
			player.getX(),
			player.getY(),
			player.getZ(),
			player.getHealth(),
			player.getMaxHealth(),
			player.getHungerManager().getFoodLevel(),
			player.getHungerManager().getSaturationLevel(),
			player.isOnGround(),
			player.fallDistance,
			player.getGameMode().asString(),
			player.getEntityWorld().getRegistryKey().getValue().toString()
		);
	}

	private static GameMode verificationGameMode(String modeId) {
		if (modeId == null || modeId.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "mode must be survival, creative, or spectator");
		}
		GameMode mode = GameMode.byId(modeId.trim().toLowerCase(java.util.Locale.ROOT), null);
		if (mode != GameMode.SURVIVAL && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
			throw new BridgeUnavailableException("invalid_request", "mode must be survival, creative, or spectator");
		}
		return mode;
	}

	private static String normalizedVerificationCommand(String command) {
		if (command == null) {
			throw new BridgeUnavailableException("invalid_request", "Missing command");
		}
		String normalized = command.trim();
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1).trim();
		}
		if (normalized.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "Missing command");
		}
		return normalized;
	}

	private void joinFirstWorld() {
		List<Map<String, Object>> worlds = singleplayerWorldService.listWorlds();
		if (worlds.isEmpty()) {
			throw new IllegalStateException("No singleplayer worlds are available for session.basic");
		}

		Object worldName = worlds.get(0).get("name");
		if (!(worldName instanceof String worldNameValue) || worldNameValue.isBlank()) {
			throw new IllegalStateException("First singleplayer world is missing a valid internal name");
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new IllegalStateException("Minecraft client is not initialized");
		}

		client.createIntegratedServerLoader().start(worldNameValue, () -> {
		});
	}

	private void leaveCurrentWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new IllegalStateException("Minecraft client is not initialized");
		}
		if (client.world == null && client.player == null) {
			return;
		}

		client.disconnect(Text.empty());
	}

	private Vec3d playerOffset(double xOffset) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return new Vec3d(xOffset, 64.0D, 0.0D);
		}
		return new Vec3d(
			client.player.getX() + xOffset,
			client.player.getY(),
			client.player.getZ()
		);
	}

	private void setForwardKeyPressed(boolean pressed) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null) {
			throw new IllegalStateException("Minecraft client input is not initialized");
		}
		client.options.forwardKey.setPressed(pressed);
	}

	private boolean isForwardKeyPressed() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null
			&& client.options != null
			&& client.options.forwardKey.isPressed();
	}

	private float resolveEffectiveHealthBefore(float observedHealthBefore, float healthAfter) {
		return effectiveHealthBefore(lastKnownPlayerHealth, observedHealthBefore, healthAfter);
	}

	private static Float currentPlayerHealth(MinecraftClient client) {
		if (client == null || client.player == null || !client.isOnThread()) {
			return null;
		}
		return client.player.getHealth();
	}

	static float effectiveHealthBefore(Float lastKnownPlayerHealth, float observedHealthBefore, float healthAfter) {
		if (lastKnownPlayerHealth != null
			&& Float.isFinite(lastKnownPlayerHealth.floatValue())
			&& lastKnownPlayerHealth.floatValue() > healthAfter
		) {
			return lastKnownPlayerHealth.floatValue();
		}
		return observedHealthBefore;
	}
}
