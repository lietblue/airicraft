package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.AiricraftConfigLoader;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.SingleplayerWorldService;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeRuntime;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeSnapshot;
import ai.moeru.airicraft.agent.chat.ChatService;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
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
import ai.moeru.airicraft.agent.goals.GoalPosition;
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
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.session.LanHostingService;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.session.SessionRuntime;
import ai.moeru.airicraft.agent.social.ChatIngestService;
import ai.moeru.airicraft.agent.social.NearbyPlayerSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import ai.moeru.airicraft.agent.social.PrimaryInteractionPlayer;
import ai.moeru.airicraft.agent.social.PrimaryInteractionResolver;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.CollectResourceTaskHandler;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.InventoryResourceCounter;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskRuntime;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
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
import ai.moeru.airicraft.agent.verification.scenarios.NavigateVerification;
import ai.moeru.airicraft.agent.verification.scenarios.PlannerObservabilityVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionLanVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialPrimaryInteractionTtlVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialChatIngestVerification;
import ai.moeru.airicraft.agent.session.SessionMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
	private final WorldTaskExecutor worldTaskExecutor;
	private final InventoryResourceCounter inventoryResourceCounter = new InventoryResourceCounter();
	private final InventoryItemCounter inventoryItemCounter = new InventoryItemCounter();
	private final TaskRuntime taskRuntime = new TaskRuntime();

	private boolean initialized;
	private long tickCount;
	private long worldLoadTick = -1L;
	private Boolean proactiveSocialModeOverride;
	private SessionSnapshot sessionSnapshot = SessionSnapshot.initial();
	private SessionSnapshot sessionSnapshotOverrideForTests;
	private FollowState followState = FollowState.idle();
	private TaskSnapshot taskSnapshot = TaskSnapshot.idle();
	private TaskExecutionSnapshot taskExecutionSnapshot = TaskExecutionSnapshot.idle();
	private long lastSystemChatTick = -1L;
	private String lastSystemChatText;
	private final Map<UUID, String> seenPlayerNames = new LinkedHashMap<>();

	public EmbodiedAgentRuntime(
		AiricraftConfig airicraftConfig,
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor
	) {
		this.airicraftConfig = Objects.requireNonNull(airicraftConfig, "airicraftConfig");
		this.config = Objects.requireNonNull(config, "config");
		this.worldTaskExecutor = Objects.requireNonNull(worldTaskExecutor, "worldTaskExecutor");
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
				config.llm().visionImageDetail()
			),
			config.llm().maxRecentConversationTurns(),
			clock
		);
		registerDefaultScenarios();
	}

	public EmbodiedAgentRuntime(AiricraftConfig airicraftConfig, AgentConfig config, FirstPersonScreenshotService screenshotService) {
		this(airicraftConfig, config, screenshotService, NoopWorldTaskExecutor.INSTANCE);
	}

	public static EmbodiedAgentRuntime createDefault(
		AiricraftConfig airicraftConfig,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor
	) {
		return new EmbodiedAgentRuntime(airicraftConfig, AgentConfigLoader.load(), screenshotService, worldTaskExecutor);
	}

	public static EmbodiedAgentRuntime createDefault(AiricraftConfig airicraftConfig, FirstPersonScreenshotService screenshotService) {
		return createDefault(airicraftConfig, screenshotService, NoopWorldTaskExecutor.INSTANCE);
	}

	public static EmbodiedAgentRuntime createDefault(FirstPersonScreenshotService screenshotService) {
		return createDefault(AiricraftConfigLoader.load(), screenshotService);
	}

	static EmbodiedAgentRuntime createForTests(WorldTaskExecutor worldTaskExecutor) {
		return new EmbodiedAgentRuntime(
			AiricraftConfig.defaults(),
			AgentConfig.defaults(),
			new FirstPersonScreenshotService(),
			worldTaskExecutor
		);
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
		sessionSnapshotOverrideForTests = null;
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		primaryInteractionResolver.clear();
		dialogueRuntime.clear();
		worldTaskExecutor.onWorldLeave();
		taskRuntime.clear();
		goalDirector.clear();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
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
		sessionSnapshot = sessionSnapshotOverrideForTests != null
			? sessionSnapshotOverrideForTests.withTickCount(tickCount)
			: sessionRuntime.poll(client, tickCount, eventBuffer);
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
			applyTaskIntent(completedDialogueResponse);
			goalDirector.onPlannerResponse(completedDialogueResponse);
			recordPlannerOutcome(completedDialogueResponse, previousGoal, goalDirector.activeGoal());
		}

		TaskSnapshot previousTaskSnapshot = taskSnapshot;
		WorldEvidence worldEvidence = currentWorldEvidence(client);
		taskRuntime.tick(
			taskExecutionSnapshot,
			worldEvidence,
			sessionSnapshot.companionActuationAllowed(),
			hasNearbyTaskResourceTarget(client, taskSnapshot.spec()),
			tickCount
		);
		taskSnapshot = taskRuntime.snapshot();
		recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		Optional<GoalSnapshot> activeGoal = activeGoal();

		followState = followCapability.tick(
			client,
			sessionSnapshot,
			activeGoal,
			nearbyPlayerTracker,
			tickCount,
			eventBuffer
		);
		TaskExecutionSnapshot previousTaskExecutionSnapshot = taskExecutionSnapshot;
		Optional<TaskTerminalEvent> terminalTaskEvent = worldTaskExecutor.tick(sessionSnapshot, activeGoal);
		taskExecutionSnapshot = worldTaskExecutor.snapshot();
		boolean semanticTaskContext = hasSemanticTaskContext(previousTaskSnapshot, taskSnapshot);
		recordTaskStateTransition(previousTaskExecutionSnapshot, taskExecutionSnapshot, semanticTaskContext);
		terminalTaskEvent.ifPresent(event -> handleTerminalTaskEvent(event, semanticTaskContext));
		behaviorTreeRuntime.tick(
			client,
			sessionSnapshot,
			dialogueRuntime,
			chatService,
			activeGoal,
			followState,
			taskExecutionSnapshot,
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
		sessionSnapshotOverrideForTests = null;
		verificationRunner.reset();
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		eventBuffer.clear();
		primaryInteractionResolver.clear();
		dialogueRuntime.shutdown();
		visionService.shutdown();
		worldTaskExecutor.shutdown();
		taskRuntime.clear();
		goalDirector.clear();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
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
			taskSnapshot,
			taskExecutionSnapshot,
			taskRuntime.executionSnapshot(),
			verificationRunner.report()
		);
	}

	public VerificationReport verificationReport() {
		return verificationRunner.report();
	}

	public Optional<GoalSnapshot> activeGoal() {
		Optional<GoalSnapshot> taskOwnedGoal = taskRuntime.currentGoal();
		return taskOwnedGoal.isPresent() ? taskOwnedGoal : goalDirector.activeGoal();
	}

	public BehaviorTreeSnapshot behaviorTreeSnapshot() {
		return behaviorTreeRuntime.snapshot();
	}

	public TaskExecutionSnapshot taskExecutionSnapshot() {
		return taskExecutionSnapshot;
	}

	public TaskSnapshot taskSnapshot() {
		return taskSnapshot;
	}

	public MissionExecutionSnapshot missionExecutionSnapshot() {
		return taskRuntime.executionSnapshot();
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
					taskSnapshot,
					taskRuntime.executionSnapshot(),
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
					taskSnapshot,
					taskRuntime.executionSnapshot(),
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

			dialogueRuntime.onPlayerChat(
				"server",
				plainTextMessage,
				tickCount,
				sessionSnapshot,
				primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null),
				goalDirector.activeGoal(),
				taskSnapshot,
				taskRuntime.executionSnapshot(),
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

	public TaskSnapshot submitTask(TaskSpec spec, String source) {
		Objects.requireNonNull(spec, "spec");
		goalDirector.clear();
		taskRuntime.submit(spec, tickCount, source == null || source.isBlank() ? "bridge_debug" : source);
		taskSnapshot = taskRuntime.snapshot();
		eventBuffer.append(tickCount, "task.submitted", Map.of(
			"type", spec.type().name(),
			"resourceKind", spec.resourceKind().name(),
			"quantity", spec.quantity(),
			"source", taskSnapshot.source()
		));
		return taskSnapshot;
	}

	public TaskSnapshot submitMissionLedger(TaskLedger ledger, String source) {
		Objects.requireNonNull(ledger, "ledger");
		TaskLedger previousLedger = taskSnapshot.ledger();
		goalDirector.clear();
		taskRuntime.applyPlannerLedger(ledger, tickCount, source == null || source.isBlank() ? "bridge_debug_mission" : source);
		taskSnapshot = taskRuntime.snapshot();
		eventBuffer.append(tickCount, "mission.submitted", Map.of(
			"missionId", ledger.missionId(),
			"missionType", ledger.missionType().name(),
			"activeStepId", ledger.activeStepId() == null ? "" : ledger.activeStepId(),
			"source", taskSnapshot.source()
		));
		recordMissionLedgerUpdate(previousLedger, taskSnapshot.ledger(), taskSnapshot.source());
		return taskSnapshot;
	}

	public TaskSnapshot cancelTask(String reason) {
		taskRuntime.cancel(tickCount, reason == null || reason.isBlank() ? "cancelled" : reason);
		taskSnapshot = taskRuntime.snapshot();
		return taskSnapshot;
	}

	void injectDialogueResponseForTests(DialogueResponse response) {
		Optional<GoalSnapshot> previousGoal = goalDirector.activeGoal();
		applyTaskIntent(response);
		goalDirector.onPlannerResponse(response);
		recordPlannerOutcome(response, previousGoal, goalDirector.activeGoal());
	}

	void overrideSessionSnapshotForTests(SessionSnapshot sessionSnapshot) {
		sessionSnapshotOverrideForTests = sessionSnapshot;
		this.sessionSnapshot = sessionSnapshot == null ? SessionSnapshot.initial() : sessionSnapshot;
	}

	void injectGoalForTests(GoalSnapshot goalSnapshot) {
		goalDirector.clear();
		if (goalSnapshot == null) {
			return;
		}
		goalDirector.onPlannerResponse(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				goalSnapshot.type(),
				goalSnapshot.targetPlayer(),
				goalSnapshot.position(),
				goalSnapshot.mineSpec()
			),
			goalSnapshot.updatedTick()
		));
	}

	private void applyTaskIntent(DialogueResponse response) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}
		if (response.intent().type() == DialogueIntentType.MISSION_UPDATE && response.intent().taskLedger() != null) {
			TaskLedger previousLedger = taskSnapshot.ledger();
			goalDirector.clear();
			taskRuntime.applyPlannerLedger(response.intent().taskLedger(), response.tick(), "planner_response");
			taskSnapshot = taskRuntime.snapshot();
			recordMissionLedgerUpdate(previousLedger, taskSnapshot.ledger(), taskSnapshot.source());
			return;
		}
		if (response.intent().type() == DialogueIntentType.SUBMIT_TASK && response.intent().taskSpec() != null) {
			goalDirector.clear();
			taskRuntime.submit(response.intent().taskSpec(), response.tick(), "planner_response");
			taskSnapshot = taskRuntime.snapshot();
			return;
		}
		if (response.intent().type() == DialogueIntentType.CANCEL_TASK) {
			taskRuntime.cancel(response.tick(), "planner_cancel_task");
			taskSnapshot = taskRuntime.snapshot();
			return;
		}
		if (response.intent().type() == DialogueIntentType.SET_GOAL && taskRuntime.hasActiveTask()) {
			taskRuntime.cancel(response.tick(), "preempted_by_direct_goal");
			taskSnapshot = taskRuntime.snapshot();
		}
	}

	private int currentTaskResourceCount(MinecraftClient client) {
		if (client == null || client.player == null || taskSnapshot.spec() == null) {
			return 0;
		}
		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}
		return inventoryResourceCounter.count(stacks, taskSnapshot.spec().resourceKind());
	}

	private WorldEvidence currentWorldEvidence(MinecraftClient client) {
		if (client == null || client.player == null) {
			return new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, tickCount);
		}

		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}

		java.util.EnumMap<ai.moeru.airicraft.agent.tasks.TaskResourceKind, Integer> resourceCounts =
			new java.util.EnumMap<>(ai.moeru.airicraft.agent.tasks.TaskResourceKind.class);
		for (ai.moeru.airicraft.agent.tasks.TaskResourceKind kind : ai.moeru.airicraft.agent.tasks.TaskResourceKind.values()) {
			resourceCounts.put(kind, inventoryResourceCounter.count(stacks, kind));
		}

		String equippedItemId = Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
		BlockPos origin = client.player.getBlockPos();
		return new WorldEvidence(
			resourceCounts,
			inventoryItemCounter.count(client.player.getInventory()),
			collectNearbyBlocks(client, origin),
			client.world == null ? null : client.world.getRegistryKey().getValue().toString(),
			origin.getX(),
			origin.getY(),
			origin.getZ(),
			equippedItemId,
			tickCount
		);
	}

	private boolean hasNearbyTaskResourceTarget(MinecraftClient client, TaskSpec spec) {
		if (client == null || client.world == null || client.player == null || spec == null) {
			return false;
		}
		List<String> targetBlockIds = CollectResourceTaskHandler.targetBlockIds(spec);
		if (targetBlockIds.isEmpty()) {
			return false;
		}

		BlockPos origin = client.player.getBlockPos();
		for (int dx = -12; dx <= 12; dx++) {
			for (int dy = -6; dy <= 6; dy++) {
				for (int dz = -12; dz <= 12; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!client.world.isChunkLoaded(pos)) {
						continue;
					}
					String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
					if (targetBlockIds.contains(blockId)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private Map<String, Integer> collectNearbyBlocks(MinecraftClient client, BlockPos origin) {
		if (client == null || client.world == null) {
			return Map.of();
		}
		java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
		for (int dx = -8; dx <= 8; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -8; dz <= 8; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!client.world.isChunkLoaded(pos)) {
						continue;
					}
					String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
					counts.merge(blockId, 1, Integer::sum);
				}
			}
		}
		return Map.copyOf(counts);
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

			dialogueRuntime.onPlayerChat(
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
		if (response.intent().taskLedger() != null) {
			payload.put("missionId", response.intent().taskLedger().missionId());
			payload.put("missionType", response.intent().taskLedger().missionType().name());
			if (response.intent().taskLedger().activeStepId() != null) {
				payload.put("activeStepId", response.intent().taskLedger().activeStepId());
			}
		}
		if (response.text() != null && !response.text().isBlank()) {
			payload.put("replyText", response.text());
		}
		eventBuffer.append(tickCount, "planner.response_applied", payload);

		if (response.intent().type() == DialogueIntentType.SET_GOAL && currentGoal.isPresent()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			goalPayload.put("goalType", currentGoal.get().type().name());
			if (currentGoal.get().targetPlayer() != null && !currentGoal.get().targetPlayer().isBlank()) {
				goalPayload.put("targetPlayer", currentGoal.get().targetPlayer());
			}
			goalPayload.put("source", currentGoal.get().source());
			eventBuffer.append(tickCount, "planner.goal_set", goalPayload);
			return;
		}

		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL && previousGoal.isPresent() && currentGoal.isEmpty()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			goalPayload.put("goalType", previousGoal.get().type().name());
			if (previousGoal.get().targetPlayer() != null && !previousGoal.get().targetPlayer().isBlank()) {
				goalPayload.put("targetPlayer", previousGoal.get().targetPlayer());
			}
			goalPayload.put("source", previousGoal.get().source());
			eventBuffer.append(tickCount, "planner.goal_cleared", goalPayload);
		}
	}

	private void recordMissionLedgerUpdate(TaskLedger previousLedger, TaskLedger currentLedger, String source) {
		if (currentLedger == null) {
			return;
		}
		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("missionId", currentLedger.missionId());
		payload.put("missionType", currentLedger.missionType().name());
		payload.put("source", source == null ? "" : source);
		payload.put("previousActiveStepId", previousLedger == null || previousLedger.activeStepId() == null ? "" : previousLedger.activeStepId());
		payload.put("activeStepId", currentLedger.activeStepId() == null ? "" : currentLedger.activeStepId());
		if (currentLedger.replanReason() != null && !currentLedger.replanReason().isBlank()) {
			payload.put("replanReason", currentLedger.replanReason());
		}
		if (currentLedger.plannerNotes() != null && !currentLedger.plannerNotes().isBlank()) {
			payload.put("plannerNotes", currentLedger.plannerNotes());
		}
		payload.put("stepStatusChanges", describeLedgerStatusChanges(previousLedger, currentLedger));
		eventBuffer.append(tickCount, "mission.ledger_updated", payload);
	}

	private static java.util.Map<String, String> describeLedgerStatusChanges(TaskLedger previousLedger, TaskLedger currentLedger) {
		java.util.LinkedHashMap<String, String> changes = new java.util.LinkedHashMap<>();
		java.util.LinkedHashMap<String, ai.moeru.airicraft.agent.tasks.LedgerStepStatus> previousStatuses = new java.util.LinkedHashMap<>();
		if (previousLedger != null) {
			for (var step : previousLedger.steps()) {
				previousStatuses.put(step.id(), step.status());
			}
		}
		for (var step : currentLedger.steps()) {
			ai.moeru.airicraft.agent.tasks.LedgerStepStatus previousStatus = previousStatuses.remove(step.id());
			if (previousStatus == null) {
				changes.put(step.id(), "ADDED:" + step.status().name());
			}
			else if (previousStatus != step.status()) {
				changes.put(step.id(), previousStatus.name() + "->" + step.status().name());
			}
		}
		for (String removedStepId : previousStatuses.keySet()) {
			changes.put(removedStepId, "REMOVED");
		}
		return java.util.Map.copyOf(changes);
	}

	private void recordTaskStateTransition(TaskExecutionSnapshot previous, TaskExecutionSnapshot current, boolean semanticTaskContext) {
		if (current == null || previous == null || current.state() == previous.state()) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		if (current.activeGoal() != null) {
			payload.put("goalType", current.activeGoal().type().name());
		}
		if (current.processName() != null && !current.processName().isBlank()) {
			payload.put("process", current.processName());
		}

		if (current.state() == TaskExecutionState.RUNNING) {
			eventBuffer.append(tickCount, "task.started", payload);
			return;
		}
		if (current.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			eventBuffer.append(tickCount, "task.paused_by_session_gate", payload);
		}
	}

	private void recordSemanticTaskTransition(TaskSnapshot previous, TaskSnapshot current) {
		if (previous == null || current == null || current.state() == previous.state()) {
			return;
		}

			java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
			if (current.spec() != null) {
				payload.put("taskType", current.spec().type().name());
				payload.put("resourceKind", current.spec().resourceKind().name());
				payload.put("quantity", current.spec().quantity());
			}
			if (current.mission() != null) {
				payload.put("missionId", current.mission().missionId());
				payload.put("missionType", current.mission().missionType().name());
			}
			if (current.activeStepId() != null) {
				payload.put("activeStepId", current.activeStepId());
			}
			if (current.activeStepKind() != null) {
				payload.put("activeStepKind", current.activeStepKind().name());
			}
			payload.put("state", current.state().name());
			payload.put("collected", current.progress().collected());
			payload.put("remaining", current.progress().remaining());
		if (current.source() != null && !current.source().isBlank()) {
			payload.put("source", current.source());
		}
		if (current.lastFailure() != null && !current.lastFailure().isBlank()) {
			payload.put("failure", current.lastFailure());
		}

		String eventType = switch (current.state()) {
			case RUNNING -> "task.started";
			case PAUSED_BY_SESSION_GATE -> "task.paused_by_session_gate";
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			eventBuffer.append(tickCount, eventType, payload);
		}

		if (
			current.state() == TaskState.PAUSED_BY_SESSION_GATE
				|| current.state() == TaskState.COMPLETED
				|| current.state() == TaskState.FAILED
				|| current.state() == TaskState.CANCELLED
		) {
				dialogueRuntime.onInternalTaskUpdate(
					"TASK UPDATE: state=" + current.state().name()
						+ " missionId=" + (current.mission() == null ? "" : current.mission().missionId())
						+ " missionType=" + (current.mission() == null ? "" : current.mission().missionType().name())
						+ " activeStepId=" + (current.activeStepId() == null ? "" : current.activeStepId())
						+ " activeStepKind=" + (current.activeStepKind() == null ? "" : current.activeStepKind().name())
						+ " taskType=" + (current.spec() == null ? "" : current.spec().type().name())
						+ " resourceKind=" + (current.spec() == null ? "" : current.spec().resourceKind().name())
						+ " collected=" + current.progress().collected()
					+ " remaining=" + current.progress().remaining()
					+ " failure=" + (current.lastFailure() == null ? "" : current.lastFailure()),
					tickCount,
					sessionSnapshot,
					goalDirector.activeGoal(),
					current,
					taskRuntime.executionSnapshot(),
					eventBuffer
				);
		}
	}

	private static boolean hasSemanticTaskContext(TaskSnapshot previous, TaskSnapshot current) {
		return (previous != null && isActiveSemanticTaskState(previous.state()))
			|| (current != null && isActiveSemanticTaskState(current.state()));
	}

	private static boolean isActiveSemanticTaskState(TaskState state) {
		return state == TaskState.QUEUED
			|| state == TaskState.RUNNING
			|| state == TaskState.WAITING_FOR_PICKUP
			|| state == TaskState.PAUSED_BY_SESSION_GATE;
	}

	private void handleTerminalTaskEvent(TaskTerminalEvent event, boolean semanticTaskContext) {
		if (event == null || event.goal() == null || event.terminalState() == null) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		String eventType = switch (event.terminalState()) {
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("goalType", event.goal().type().name());
			payload.put("message", event.message() == null ? "" : event.message());
			if (event.goal().targetPlayer() != null && !event.goal().targetPlayer().isBlank()) {
				payload.put("targetPlayer", event.goal().targetPlayer());
			}
			eventBuffer.append(tickCount, eventType, payload);
		}

			dialogueRuntime.onInternalTaskUpdate(
				"TASK UPDATE: state=" + event.terminalState().name()
					+ " goalType=" + event.goal().type().name()
					+ " message=" + (event.message() == null ? "" : event.message()),
				tickCount,
				sessionSnapshot,
				goalDirector.activeGoal(),
				taskSnapshot,
				taskRuntime.executionSnapshot(),
				eventBuffer
			);
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
		GoalPosition[] navigateTarget = new GoalPosition[1];
		long[] navigateTaskBaselineSeqNo = new long[1];
		verificationRunner.register(new NavigateVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			this::openLan,
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LAN_HOST,
			() -> navigateTarget[0] = findNearbyNavigationTarget(),
			() -> navigateTaskBaselineSeqNo[0] = eventBuffer.latestSeqNo(),
			() -> injectGoalForTests(new GoalSnapshot(
				GoalType.NAVIGATE_TO,
				null,
				navigateTarget[0],
				null,
				tickCount,
				"verification"
			)),
			() -> activeGoal()
				.map(goal -> goal.type() == GoalType.NAVIGATE_TO && Objects.equals(goal.position(), navigateTarget[0]))
				.orElse(false),
			() -> taskExecutionSnapshot.state() == TaskExecutionState.RUNNING
				&& behaviorTreeSnapshot().activeNodePath().contains("NavigateToSubtree"),
			() -> eventBuffer.containsTypeSince(navigateTaskBaselineSeqNo[0], "task.completed")
				|| taskExecutionSnapshot.state() == TaskExecutionState.COMPLETED,
			() -> playerNear(navigateTarget[0], 1.75D)
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

		Object worldId = worlds.get(0).get("worldId");
		if (!(worldId instanceof String worldIdValue) || worldIdValue.isBlank()) {
			throw new IllegalStateException("First singleplayer world is missing a valid worldId");
		}

		singleplayerWorldService.joinWorld(worldIdValue);
	}

	private void leaveCurrentWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new IllegalStateException("Minecraft client is not initialized");
		}
		if (client.world == null && client.player == null) {
			return;
		}

		client.disconnect(null, false);
	}

	private GoalPosition findNearbyNavigationTarget() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			throw new IllegalStateException("Minecraft world is not loaded");
		}

		BlockPos origin = client.player.getBlockPos();
		for (int radius = 1; radius <= 8; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
						continue;
					}
					GoalPosition candidate = findWalkableNavigationTargetInColumn(
						client,
						origin.getX() + dx,
						origin.getZ() + dz,
						origin.getY()
					);
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}

		throw new IllegalStateException("No nearby walkable navigation target was found");
	}

	private GoalPosition findWalkableNavigationTargetInColumn(
		MinecraftClient client,
		int x,
		int z,
		int originY
	) {
		for (int y = originY + 1; y >= originY - 6; y--) {
			BlockPos candidate = new BlockPos(x, y, z);
			if (isWalkableNavigationTarget(client, candidate)) {
				return new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), true);
			}
		}
		return null;
	}

	private boolean isWalkableNavigationTarget(MinecraftClient client, BlockPos target) {
		if (client.world == null || client.player == null) {
			return false;
		}
		if (target.equals(client.player.getBlockPos())) {
			return false;
		}
		BlockPos below = target.down();
		BlockPos above = target.up();
		return client.world.isAir(target)
			&& client.world.isAir(above)
			&& client.world.getBlockState(below).isSideSolidFullSquare(client.world, below, Direction.UP);
	}

	private boolean playerNear(GoalPosition target, double maxDistance) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (target == null || client == null || client.player == null) {
			return false;
		}
		Vec3d center = new Vec3d(target.x() + 0.5D, target.y(), target.z() + 0.5D);
		return client.player.getPos().squaredDistanceTo(center) <= maxDistance * maxDistance;
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

	private static final class NoopWorldTaskExecutor implements WorldTaskExecutor {
		private static final NoopWorldTaskExecutor INSTANCE = new NoopWorldTaskExecutor();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<GoalSnapshot> activeGoal) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}
}
