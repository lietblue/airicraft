package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PlannerOrchestrator {
	private static final String VISUAL_TOOL_NAME = "take_a_look";
	private static final String NATIVE_TOOL_RESULT_TEXT = "Tool result for take_a_look: current first-person view attached.";

	private final PlannerExecutor plannerExecutor;
	private final PlannerCompactionService compactionService;
	private final PlannerContextAggregator contextAggregator;
	private final CurrentViewVisionTool visionTool;
	private final PlannerVisionMode visionMode;
	private final String imageDetail;
	private final AgentObservability observability;

	private PlannerRequest baseRequest;
	private boolean toolUsed;
	private volatile boolean captureInFlight;
	private CompletableFuture<ToolExecutionOutcome> toolResultFuture;
	private CompactionExecutionResult lastCompactionResult;
	private Context turnContext;

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail
	) {
		this(plannerExecutor, compactionService, contextAggregator, visionTool, visionMode, imageDetail, NoopObservability.INSTANCE);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		AgentObservability observability
	) {
		this.plannerExecutor = Objects.requireNonNull(plannerExecutor, "plannerExecutor");
		this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
		this.contextAggregator = Objects.requireNonNull(contextAggregator, "contextAggregator");
		this.visionTool = Objects.requireNonNull(visionTool, "visionTool");
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
		this.imageDetail = Objects.requireNonNull(imageDetail, "imageDetail");
		this.observability = Objects.requireNonNull(observability, "observability");
	}

	public boolean isConfigured() {
		return plannerExecutor.isConfigured();
	}

	public boolean hasInFlight() {
		return plannerExecutor.hasInFlight() || compactionService.hasInFlight() || toolResultFuture != null;
	}

	public PlannerOrchestratorDebugSnapshot debugSnapshot() {
		return new PlannerOrchestratorDebugSnapshot(
			isConfigured(),
			visionMode.wireValue(),
			hasInFlight(),
			plannerExecutor.hasInFlight(),
			compactionService.hasInFlight(),
			captureInFlight,
			toolResultFuture != null,
			toolUsed,
			baseRequest,
			lastCompactionResult,
			contextAggregator.debugSnapshot()
		);
	}

	public boolean submit(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (hasInFlight()) {
			return false;
		}

		baseRequest = request;
		toolUsed = false;
		turnContext = observability.startTurnSpan(request, buildTurnId(request));
		if (contextAggregator.compactionPending()) {
			return compactionService.submit(contextAggregator.buildCompactionConversation(), turnContext);
		}
		return submitPlannerConversation(request);
	}

	public PlannerExecutionResult poll() {
		if (compactionService.hasInFlight()) {
			CompactionExecutionResult compactionResult = compactionService.poll();
			if (compactionResult == null) {
				return null;
			}
			completeCompaction(compactionResult);
			if (baseRequest == null) {
				endTurnSpan();
				return null;
			}
			if (!submitPlannerConversation(baseRequest)) {
				PlannerExecutionResult failure = new PlannerExecutionResult(
					baseRequest,
					null,
					LlmUsageSnapshot.unknown(),
					LlmFailureType.PROVIDER_ERROR,
					"Planner request could not be submitted after compaction"
				);
				observability.recordFailure(turnContext, LlmFailureType.PROVIDER_ERROR.name(), failure.failureMessage(), null);
				clearState();
				return failure;
			}
			return null;
		}

		if (toolResultFuture != null) {
			if (!toolResultFuture.isDone()) {
				return null;
			}
			return continueAfterTool();
		}

		PlannerExecutionResult plannerResult = plannerExecutor.poll();
		if (plannerResult == null) {
			return null;
		}
		if (!plannerResult.succeeded()) {
			observability.recordFailure(turnContext, plannerResult.failureType().name(), plannerResult.failureMessage(), null);
			clearState();
			return plannerResult;
		}

		contextAggregator.recordUsage(plannerResult.usage());
		PlannerToolRequest toolRequest = plannerResult.response().toolRequest();
		if (toolRequest == null) {
			clearState();
			return plannerResult;
		}

		if (toolUsed) {
			Airicraft.LOGGER.warn(
				"Planner returned repeated tool request type={} prompt={}",
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt())
			);
			clearState();
			return parseFailure("Planner requested take_a_look more than once");
		}
		String toolIntentType = toolIntentType(plannerResult.response());
		if (!hasToolCompatibleIntent(plannerResult.response())) {
			Airicraft.LOGGER.warn(
				"Planner returned invalid tool response intentType={} toolRequestType={} toolPrompt={} replyText={}",
				toolIntentType,
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt()),
				summarizeForLog(plannerResult.response().replyText())
			);
			clearState();
			return parseFailure("Tool requests cannot set goal intents");
		}
		if (!isValidToolRequest(toolRequest)) {
			Airicraft.LOGGER.warn(
				"Planner returned invalid tool request type={} prompt={}",
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt())
			);
			clearState();
			return parseFailure("Planner requested an invalid tool");
		}
		if (!"none".equals(toolIntentType)) {
			Airicraft.LOGGER.info(
				"Planner returned tool request with non-none intent; ignoring intentType={} toolRequestType={}",
				toolIntentType,
				toolRequest.type()
			);
		}
		if (plannerResult.response().replyText() != null && !plannerResult.response().replyText().isBlank()) {
			Airicraft.LOGGER.info(
				"Planner returned tool request with stray replyText; ignoring text={} toolRequestType={}",
				summarizeForLog(plannerResult.response().replyText()),
				toolRequest.type()
			);
		}

		toolUsed = true;
		toolResultFuture = requestVisionTool(toolRequest);
		return null;
	}

	public void injectMockResponse(PlannerResponse response) {
		plannerExecutor.injectMockResponse(response);
	}

	public void injectTimeout() {
		plannerExecutor.injectTimeout();
	}

	public void recordAssistantTurn(DialogueTurn turn) {
		contextAggregator.recordAgentTurn(turn);
	}

	public void recordEvents(java.util.List<SemanticEvent> events, long anchorTimeMs) {
		contextAggregator.recordEvents(events, anchorTimeMs);
	}

	public boolean startDebugCompaction() {
		if (!isConfigured() || hasInFlight()) {
			return false;
		}
		lastCompactionResult = null;
		return compactionService.submit(contextAggregator.buildCompactionConversation());
	}

	public CompactionExecutionResult pollDebugCompaction() {
		if (!compactionService.hasInFlight()) {
			return lastCompactionResult;
		}
		CompactionExecutionResult compactionResult = compactionService.poll();
		if (compactionResult == null) {
			return null;
		}
		completeCompaction(compactionResult);
		return compactionResult;
	}

	public void reset() {
		clearState();
		plannerExecutor.reset();
		compactionService.reset();
		contextAggregator.clear();
		lastCompactionResult = null;
	}

	public void shutdown() {
		reset();
		plannerExecutor.shutdown();
		compactionService.shutdown();
	}

	private boolean submitPlannerConversation(PlannerRequest request) {
		try (Scope scope = currentTurnContext().makeCurrent()) {
			return plannerExecutor.submit(
				request,
				contextAggregator.buildPlannerConversation(request),
				turnContext,
				AgentObservability.PLANNER_REQUEST_SPAN_NAME
			);
		}
	}

	private PlannerExecutionResult continueAfterTool() {
		if (turnContext == null) {
			PlannerExecutionResult failure = new PlannerExecutionResult(
				baseRequest,
				null,
				LlmUsageSnapshot.unknown(),
				LlmFailureType.PROVIDER_ERROR,
				"Planner turn context missing"
			);
			clearState();
			return failure;
		}
		ToolExecutionOutcome toolOutcome;
		try {
			toolOutcome = toolResultFuture.join();
		}
		catch (CompletionException exception) {
			toolOutcome = new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_failed");
			Airicraft.LOGGER.warn("Planner tool future failed sender={}", baseRequest == null ? null : baseRequest.senderName(), exception);
		}
		finally {
			toolResultFuture = null;
			captureInFlight = false;
		}

		String toolResultText = toolOutcome.toolResultText();
		PlannerRequest followUpRequest = new PlannerRequest(
			baseRequest.tick(),
			baseRequest.timestampMs(),
			baseRequest.sessionMode(),
			baseRequest.primaryInteractionPlayer(),
			baseRequest.activeGoal(),
			baseRequest.senderName(),
			baseRequest.message(),
			toolResultText
		);
		boolean submitted;
		try (Scope scope = currentTurnContext().makeCurrent()) {
			submitted = plannerExecutor.submit(
				followUpRequest,
				toolOutcome.appendFollowUp(contextAggregator),
				turnContext,
				AgentObservability.FOLLOW_UP_SPAN_NAME
			);
		}
		if (!submitted) {
			PlannerExecutionResult failure = new PlannerExecutionResult(
				followUpRequest,
				null,
				LlmUsageSnapshot.unknown(),
				LlmFailureType.PROVIDER_ERROR,
				"Planner follow-up request could not be submitted"
			);
			observability.recordFailure(turnContext, LlmFailureType.PROVIDER_ERROR.name(), failure.failureMessage(), null);
			clearState();
			return failure;
		}
		return null;
	}

	private CompletableFuture<ToolExecutionOutcome> requestVisionTool(PlannerToolRequest toolRequest) {
		Context parentContext = currentTurnContext();
		try (Scope scope = parentContext.makeCurrent()) {
			if (visionMode == PlannerVisionMode.EXTERNAL_SUMMARY) {
				if (!visionTool.isConfigured()) {
					return CompletableFuture.completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_provider_unavailable"));
				}

				return requestCapture()
					.handle((capture, throwable) -> {
						if (throwable != null) {
							String code = visionFailureCode(throwable);
							Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
							return CompletableFuture.<ToolExecutionOutcome>completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code));
						}
						return visionTool.requestDescription(capture, toolRequest.prompt())
							.<ToolExecutionOutcome>handle((description, throwable2) -> {
								if (throwable2 == null) {
									return new TextToolExecutionOutcome(description.text());
								}
								String code = visionFailureCode(throwable2);
								Airicraft.LOGGER.warn("Vision tool failed code={}", code, throwable2);
								return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
							});
					})
					.thenCompose(future -> future);
			}

			return requestCapture().handle((capture, throwable) -> {
				if (throwable == null) {
					return new ImageToolExecutionOutcome(
						NATIVE_TOOL_RESULT_TEXT,
						new LlmImageAttachment(mimeType(capture), capture.imageBytes(), imageDetail)
					);
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
				return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
			});
		}
	}

	private CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
		captureInFlight = true;
		try {
			return visionTool.requestCapture().whenComplete((capture, throwable) -> captureInFlight = false);
		}
		catch (RuntimeException exception) {
			captureInFlight = false;
			return CompletableFuture.failedFuture(exception);
		}
	}

	private static String mimeType(FirstPersonScreenshotService.CapturedScreenshot capture) {
		return "image/" + capture.format().toLowerCase(Locale.ROOT);
	}

	private boolean isValidToolRequest(PlannerToolRequest toolRequest) {
		if (!VISUAL_TOOL_NAME.equals(toolRequest.type())) {
			return false;
		}
		if (visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE) {
			return true;
		}
		return toolRequest.prompt() != null && !toolRequest.prompt().isBlank();
	}

	private static boolean hasToolCompatibleIntent(PlannerResponse response) {
		return switch (toolIntentType(response)) {
			case "none", "reply_only", "ask_clarification", "acknowledge_failure" -> true;
			default -> false;
		};
	}

	private static String toolIntentType(PlannerResponse response) {
		PlannerIntent intent = response.intent();
		return intent == null || intent.type() == null ? "none" : intent.type();
	}

	private PlannerExecutionResult parseFailure(String message) {
		return new PlannerExecutionResult(baseRequest, null, LlmUsageSnapshot.unknown(), LlmFailureType.PARSE_ERROR, message);
	}

	private static String buildTurnId(PlannerRequest request) {
		if (request == null) {
			return "session:none";
		}
		String sender = request.senderName() == null || request.senderName().isBlank() ? "unknown" : request.senderName();
		String mode = request.sessionMode() == null ? "unknown_mode" : request.sessionMode().name();
		return "session:" + mode + ":sender=" + sender + ":tick=" + request.tick();
	}

	private Context currentTurnContext() {
		return turnContext == null ? Context.current() : turnContext;
	}

	private void endTurnSpan() {
		if (turnContext != null) {
			observability.endSpan(turnContext);
			turnContext = null;
		}
	}

	private void completeCompaction(CompactionExecutionResult compactionResult) {
		lastCompactionResult = compactionResult;
		if (compactionResult.succeeded()) {
			contextAggregator.recordObservedUsage(compactionResult.usage());
			contextAggregator.applyCheckpoint(compactionResult.checkpoint());
			return;
		}
		Airicraft.LOGGER.warn("Planner compaction failed message={}", summarizeForLog(compactionResult.failureMessage()));
		contextAggregator.onCompactionFailure();
	}

	private void clearState() {
		endTurnSpan();
		baseRequest = null;
		toolUsed = false;
		toolResultFuture = null;
		captureInFlight = false;
	}

	private sealed interface ToolExecutionOutcome permits TextToolExecutionOutcome, ImageToolExecutionOutcome {
		String toolResultText();

		LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator);
	}

	private record TextToolExecutionOutcome(String toolResultText) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator) {
			return contextAggregator.buildPlannerFollowUpConversation(toolResultText);
		}
	}

	private record ImageToolExecutionOutcome(String toolResultText, LlmImageAttachment imageAttachment) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator) {
			return contextAggregator.buildPlannerFollowUpConversation(toolResultText, imageAttachment);
		}
	}

	private static String visionFailureCode(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause()
			: throwable;
		if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
			return bridgeUnavailableException.code();
		}
		if (cause instanceof LlmBackendException backendException) {
			return switch (backendException.failureType()) {
				case PROVIDER_UNAVAILABLE -> "vision_provider_unavailable";
				case TIMEOUT -> "vision_timeout";
				case PROVIDER_ERROR, PARSE_ERROR -> "vision_failed";
			};
		}
		return "vision_failed";
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
