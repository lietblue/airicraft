package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.AiricraftConfigLoader;
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
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
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
import ai.moeru.airicraft.agent.verification.scenarios.DialogueVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueChatSanitizationVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueClearGoalVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueProactiveSocialModeVerification;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;

public final class EmbodiedAgentRuntime {
	static final long CHAT_ECHO_SUPPRESSION_TICKS = 40L;

	private final AiricraftConfig airicraftConfig;
	private final AgentConfig config;
	private final VerificationRunner verificationRunner = new VerificationRunner();
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final SessionRuntime sessionRuntime = new SessionRuntime();
	private final LanHostingService lanHostingService = new LanHostingService();
	private final SemanticEventBuffer eventBuffer = new SemanticEventBuffer(512);
	private final ChatIngestService chatIngestService = new ChatIngestService();
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
				new PlannerContextAggregator(clock, config.llm().plannerCompactionTriggerTokens(), config.llm().plannerVisionMode()),
				visionService,
				config.llm().plannerVisionMode(),
				config.llm().visionImageDetail(),
				config.llm().plannerSessionMaxConcurrentAttempts()
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
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		primaryInteractionResolver.clear();
		dialogueRuntime.clear();
		goalDirector.clear();
		followCapability.clear();
		followState = FollowState.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		seenPlayerNames.clear();
	}

	public void onClientTick(MinecraftClient client) {
		tickCount++;
		FollowState previousFollowState = followState;
		BehaviorTreeSnapshot previousTreeSnapshot = behaviorTreeRuntime.snapshot();
		boolean wasWorldLoaded = sessionSnapshot.worldLoaded();
		sessionSnapshot = sessionRuntime.poll(client, tickCount, eventBuffer);
		if (!wasWorldLoaded && sessionSnapshot.worldLoaded()) {
			worldLoadTick = tickCount;
		}

		nearbyPlayerTracker.poll(client, tickCount, eventBuffer);
		primaryInteractionResolver.current().ifPresent(current ->
			primaryInteractionResolver.clearIfNotNearby(current.uuid(), nearbyPlayerTracker.isNearby(current.uuid()))
		);
		primaryInteractionResolver.expireInactive(tickCount);

		DialogueResponse completedDialogueResponse = dialogueRuntime.poll(tickCount, eventBuffer);
		if (completedDialogueResponse != null) {
			Optional<GoalSnapshot> previousGoal = goalDirector.activeGoal();
			goalDirector.onPlannerResponse(completedDialogueResponse);
			recordPlannerOutcome(completedDialogueResponse, previousGoal, goalDirector.activeGoal());
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

		verificationRunner.onTick();
	}

	public void shutdown() {
		initialized = false;
		tickCount = 0L;
		worldLoadTick = -1L;
		verificationRunner.reset();
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		eventBuffer.clear();
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
				return;
			}

				dialogueRuntime.onPlayerChat(
					plannerSender,
					plainTextMessage,
					tickCount,
					sessionSnapshot,
					primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
					goalDirector.activeGoal(),
					eventBuffer
				);
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
			return;
		}

		boolean plannerEligibleChat = ChatIngestService.isAddressedToAgent(plainTextMessage)
			|| proactiveSocialModeEnabled();
		if (plannerEligibleChat && playerChatWithinConfiguredDistance(senderName)) {
				dialogueRuntime.onPlayerChat(
					senderName,
					plainTextMessage,
					tickCount,
					sessionSnapshot,
					primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
					goalDirector.activeGoal(),
					eventBuffer
				);
			}
		}

	public void onSystemChatReceived(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		if (!proactiveSocialModeEnabled()) {
			return;
		}

			dialogueRuntime.onContextTrigger(
				PlannerTriggerType.SYSTEM,
				"server",
				plainTextMessage,
				tickCount,
				sessionSnapshot,
				primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
				goalDirector.activeGoal(),
				eventBuffer
			);
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

		dialogueRuntime.onContextTrigger(
			PlannerTriggerType.CRAFT,
			"self",
			"I crafted " + count + "x " + itemId + ".",
			tickCount,
			sessionSnapshot,
			primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
			goalDirector.activeGoal(),
			eventBuffer
		);
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

		dialogueRuntime.onContextTrigger(
			PlannerTriggerType.PICKUP,
			"self",
			"Picked up " + count + "x " + itemId + ".",
			tickCount,
			sessionSnapshot,
			primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
			goalDirector.activeGoal(),
			eventBuffer
		);
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
		if (!proactiveSocialModeEnabled()) {
			return;
		}

			dialogueRuntime.onContextTrigger(
				PlannerTriggerType.SYSTEM,
				"server",
				plainTextMessage,
				tickCount,
				sessionSnapshot,
				primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
				goalDirector.activeGoal(),
				eventBuffer
			);
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
}
