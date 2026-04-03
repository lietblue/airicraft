package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionTool;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DialogueRuntime {
	private static final int DEGRADED_FAILURE_THRESHOLD = 3;
	private static final String RESET_COMMAND = "@agent reset";
	private static final String DEGRADED_MESSAGE = "I'm having trouble understanding right now. Send '@agent reset' to recover my planner.";
	private static final String RESET_MESSAGE = "Planner state reset.";
	private static final String PARSE_ERROR_MESSAGE = "I got confused for a moment.";

	private final PlannerOrchestrator plannerOrchestrator;
	private final Clock clock;
	private final int maxRecentTurns;
	private final List<DialogueTurn> recentTurns = new ArrayList<>();

	private DialogueResponse lastResponse;
	private boolean pendingReply;
	private boolean degraded;
	private int consecutiveFailureCount;
	private int queuedTimeoutInjections;
	private LlmFailureType lastFailureType;
	private long lastFailureTick = -1L;

	public DialogueRuntime() {
		this(defaultOrchestrator(ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults(), Clock.systemDefaultZone()), 8, Clock.systemDefaultZone());
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns) {
		this(plannerOrchestrator, maxRecentTurns, Clock.systemDefaultZone());
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock) {
		this.plannerOrchestrator = plannerOrchestrator;
		this.clock = clock;
		this.maxRecentTurns = Math.max(1, maxRecentTurns);
	}

	public DialogueRuntime(PlannerExecutor plannerExecutor, int maxRecentTurns) {
		this(
			new PlannerOrchestrator(
				plannerExecutor,
				new PlannerCompactionService(new OpenAiCompatibleChatClient(ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults())),
				new PlannerContextAggregator(
					Clock.systemDefaultZone(),
					ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults().plannerCompactionTriggerTokens(),
					ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults().plannerVisionMode()
				),
				CurrentViewVisionTool.disabled(),
				ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults().plannerVisionMode(),
				ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults().visionImageDetail(),
				ai.moeru.airicraft.agent.AgentConfig.LlmConfig.defaults().plannerSessionMaxConcurrentAttempts()
			),
			maxRecentTurns,
			Clock.systemDefaultZone()
		);
	}

	public void recordResponse(DialogueResponse response) {
		lastResponse = response;
		pendingReply = response != null && response.text() != null && !response.text().isBlank();
	}

	public Optional<DialogueResponse> lastResponse() {
		return Optional.ofNullable(lastResponse);
	}

	public boolean hasPendingReply() {
		return pendingReply;
	}

	public void markReplyObserved() {
		pendingReply = false;
	}

	public boolean isDegraded() {
		return degraded;
	}

	public boolean llmAvailable() {
		return plannerOrchestrator.isConfigured();
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return plannerOrchestrator.debugSnapshot();
	}

	public boolean startDebugCompaction() {
		return plannerOrchestrator.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return plannerOrchestrator.pollDebugCompaction();
	}

	public long lastFailureTick() {
		return lastFailureTick;
	}

	public int consecutiveFailureCount() {
		return consecutiveFailureCount;
	}

	public LlmFailureType lastFailureType() {
		return lastFailureType;
	}

	public DialogueSnapshot snapshot() {
		return new DialogueSnapshot(
			List.copyOf(recentTurns),
			lastResponse,
			degraded,
			consecutiveFailureCount,
			lastFailureType,
			lastFailureTick
		);
	}

	public void injectMockResponse(PlannerResponse response) {
		plannerOrchestrator.injectMockResponse(response);
	}

	public void injectTimeout() {
		queuedTimeoutInjections++;
	}

	public boolean handleResetCommand(String senderName, String plainTextMessage, long tick, SemanticEventBuffer eventBuffer) {
		if (!isResetCommand(plainTextMessage)) {
			return false;
		}

		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, clock.millis()));
		eventBuffer.append(tick, "planner.reset_requested", Map.of(
			"player", senderName
		));
		resetLlmState(tick, eventBuffer);
		recordResponse(new DialogueResponse(
			RESET_MESSAGE,
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, senderName),
			tick
		));
		appendTurn(new DialogueTurn(DialogueSpeakerLabels.AGENT, RESET_MESSAGE, tick, clock.millis()));
		return true;
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, timestampMs));
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				PlannerTriggerType.CHAT,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs
		);
	}

	public void onContextTrigger(
		PlannerTriggerType triggerType,
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				triggerType,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs
		);
	}

	public DialogueResponse poll(long tick, SemanticEventBuffer eventBuffer) {
		if (queuedTimeoutInjections > 0 && !plannerOrchestrator.hasInFlight()) {
			queuedTimeoutInjections--;
			onFailure(LlmFailureType.TIMEOUT, "Injected LLM timeout", tick, eventBuffer);
			return null;
		}

		PlannerExecutionResult result = plannerOrchestrator.poll();
		if (result == null) {
			return null;
		}

		if (!result.succeeded()) {
			onFailure(result.failureType(), result.failureMessage(), tick, eventBuffer);
			return null;
		}

		consecutiveFailureCount = 0;
		PlannerResponse plannerResponse = result.response();
		DialogueIntentType mappedIntentType = DialogueIntentType.fromWire(plannerResponse.intent().type()).orElse(null);
		if (mappedIntentType == null) {
			eventBuffer.append(tick, "planner.unknown_intent", Map.of(
				"type", plannerResponse.intent().type()
			));
			mappedIntentType = DialogueIntentType.NONE;
		}

		DialogueResponse response = new DialogueResponse(
			plannerResponse.replyText() == null ? "" : plannerResponse.replyText(),
			new DialogueIntent(mappedIntentType, plannerResponse.intent().goalType(), plannerResponse.intent().targetPlayer()),
			tick
		);
		recordResponse(response);
		if (response.text() != null && !response.text().isBlank()) {
			recordAgentTurn(response.text(), tick);
		}
		plannerOrchestrator.onAcceptedReplyRecorded();
		return response;
	}

	public void resetLlmState(long tick, SemanticEventBuffer eventBuffer) {
		boolean wasDegraded = degraded;
		plannerOrchestrator.reset();
		degraded = false;
		consecutiveFailureCount = 0;
		queuedTimeoutInjections = 0;
		lastFailureType = null;
		lastFailureTick = -1L;
		if (wasDegraded) {
			eventBuffer.append(tick, "planner.degraded_cleared", Map.of());
		}
	}

	public void clear() {
		lastResponse = null;
		pendingReply = false;
		degraded = false;
		consecutiveFailureCount = 0;
		queuedTimeoutInjections = 0;
		lastFailureType = null;
		lastFailureTick = -1L;
		recentTurns.clear();
		plannerOrchestrator.reset();
	}

	public void shutdown() {
		clear();
		plannerOrchestrator.shutdown();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		if (plainTextMessage == null) {
			return false;
		}
		return plainTextMessage.stripLeading().equalsIgnoreCase(RESET_COMMAND);
	}

	private void submitPlannerTrigger(
		PlannerRequest request,
		SemanticEventBuffer eventBuffer,
		long timestampMs
	) {
		if (degraded) {
			return;
		}
		plannerOrchestrator.recordEvents(eventBuffer.query(null).events(), timestampMs);
		plannerOrchestrator.submit(request);
	}

	private void onFailure(LlmFailureType failureType, String failureMessage, long tick, SemanticEventBuffer eventBuffer) {
		lastFailureType = failureType;
		lastFailureTick = tick;
		consecutiveFailureCount++;

		String eventType = switch (failureType) {
			case TIMEOUT -> "planner.timeout";
			case PARSE_ERROR -> "planner.parse_error";
			case PROVIDER_ERROR, PROVIDER_UNAVAILABLE -> "planner.provider_error";
		};
		eventBuffer.append(tick, eventType, Map.of(
			"failureType", failureType.name(),
			"message", failureMessage == null ? "" : failureMessage
		));

		if (failureType == LlmFailureType.PARSE_ERROR) {
			recordResponse(new DialogueResponse(
				PARSE_ERROR_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
			recordAgentTurn(PARSE_ERROR_MESSAGE, tick);
		}

		if (consecutiveFailureCount >= DEGRADED_FAILURE_THRESHOLD && !degraded) {
			degraded = true;
			eventBuffer.append(tick, "planner.degraded_entered", Map.of(
				"failureType", failureType.name(),
				"consecutiveFailureCount", consecutiveFailureCount
			));
			recordResponse(new DialogueResponse(
				DEGRADED_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
			recordAgentTurn(DEGRADED_MESSAGE, tick);
		}
	}

	private void recordAgentTurn(String text, long tick) {
		DialogueTurn turn = new DialogueTurn(DialogueSpeakerLabels.AGENT, text, tick, clock.millis());
		appendTurn(turn);
		plannerOrchestrator.recordAssistantTurn(turn);
	}

	private void appendTurn(DialogueTurn turn) {
		recentTurns.add(turn);
		while (recentTurns.size() > maxRecentTurns) {
			recentTurns.remove(0);
		}
	}

	private static PlannerOrchestrator defaultOrchestrator(ai.moeru.airicraft.agent.AgentConfig.LlmConfig config, Clock clock) {
		return new PlannerOrchestrator(
			new PlannerExecutor(new OpenAiCompatibleLlmBackend(config)),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerVisionMode()),
			CurrentViewVisionTool.disabled(),
			config.plannerVisionMode(),
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts()
		);
	}
}
