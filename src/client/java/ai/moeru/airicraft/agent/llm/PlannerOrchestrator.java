package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PlannerOrchestrator {
	private static final String VISUAL_TOOL_NAME = "take_a_look";
	private static final String NATIVE_TOOL_RESULT_TEXT = "Tool result for take_a_look: current first-person view attached.";
	private static final int SESSION_MAX_ATTEMPTS = 2;
	private static final long SESSION_RETRY_BACKOFF_MS = 250L;

	private final PlannerExecutor plannerExecutor;
	private final PlannerCompactionService compactionService;
	private final PlannerContextAggregator contextAggregator;
	private final PlannerSessionCoordinator sessionCoordinator;
	private final CurrentViewVisionTool visionTool;
	private final PlannerVisionMode visionMode;
	private final String imageDetail;

	private PlannerRequest pendingSubmitRequest;
	private PendingToolExecution pendingToolExecution;
	private CompactionExecutionResult lastCompactionResult;
	private boolean awaitingAcceptedReplyRecord;
	private volatile boolean captureInFlight;

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			3,
			Clock.systemDefaultZone()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			Clock.systemDefaultZone()
		);
	}

	PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		Clock clock
	) {
		this.plannerExecutor = Objects.requireNonNull(plannerExecutor, "plannerExecutor");
		this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
		this.contextAggregator = Objects.requireNonNull(contextAggregator, "contextAggregator");
		this.sessionCoordinator = new PlannerSessionCoordinator(
			plannerExecutor,
			Objects.requireNonNull(clock, "clock"),
			plannerSessionMaxConcurrentAttempts,
			SESSION_MAX_ATTEMPTS,
			SESSION_RETRY_BACKOFF_MS
		);
		this.visionTool = Objects.requireNonNull(visionTool, "visionTool");
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
		this.imageDetail = Objects.requireNonNull(imageDetail, "imageDetail");
	}

	public boolean isConfigured() {
		return plannerExecutor.isConfigured();
	}

	public boolean hasInFlight() {
		return sessionCoordinator.hasInFlight() || compactionService.hasInFlight() || pendingToolExecution != null;
	}

	public PlannerOrchestratorDebugSnapshot debugSnapshot() {
		PlannerSessionSnapshot activeSession = sessionCoordinator.activeSnapshot();
		PlannerSessionPhase currentPhase = activeSession == null ? null : activeSession.phase();
		return new PlannerOrchestratorDebugSnapshot(
			isConfigured(),
			visionMode.wireValue(),
			hasInFlight(),
			sessionCoordinator.activeAttemptCount() > 0,
			compactionService.hasInFlight(),
			captureInFlight,
			pendingToolExecution != null,
			currentPhase == PlannerSessionPhase.TOOL_WAIT || currentPhase == PlannerSessionPhase.TOOL_FOLLOW_UP,
			activeSession == null ? pendingSubmitRequest : activeSession.request(),
			lastCompactionResult,
			contextAggregator.debugSnapshot(),
			sessionCoordinator.activeGeneration(),
			currentPhase == null ? null : currentPhase.name(),
			sessionCoordinator.activeAttemptCount(),
			sessionCoordinator.pendingNewestGeneration(),
			sessionCoordinator.supersededCount(),
			activeSession != null && activeSession.retryPending(),
			activeSession == null ? -1L : activeSession.retryReadyAtMs()
		);
	}

	public boolean submit(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.triggerBatch() == null || request.triggerBatch().isEmpty()) {
			return false;
		}
		for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
			contextAggregator.enqueueTrigger(trigger);
		}
		pendingSubmitRequest = request;
		if (compactionService.hasInFlight()) {
			return true;
		}
		return startQueuedWorkIfPossible();
	}

	public PlannerExecutionResult poll() {
		if (compactionService.hasInFlight()) {
			CompactionExecutionResult compactionResult = compactionService.poll();
			if (compactionResult == null) {
				return null;
			}
			completeCompaction(compactionResult);
			if (compactionResult.succeeded() && pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers()) {
				startQueuedWorkIfPossible();
			}
			return null;
		}

		if (pendingToolExecution != null) {
			PlannerExecutionResult toolContinuation = continueAfterTool();
			if (toolContinuation != null) {
				return toolContinuation;
			}
		}

		PlannerExecutionResult plannerResult = sessionCoordinator.poll();
		if (plannerResult == null) {
			return null;
		}
		if (!plannerResult.succeeded()) {
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return plannerResult;
		}

		contextAggregator.recordUsage(plannerResult.usage());
		PlannerToolRequest toolRequest = plannerResult.response().toolRequest();
		if (toolRequest == null) {
			acceptGeneration(plannerResult);
			return plannerResult;
		}
		if (plannerResult.phase() == PlannerSessionPhase.TOOL_FOLLOW_UP) {
			PlannerExecutionResult failure = parseFailure(plannerResult, "Planner requested take_a_look more than once");
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
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
			PlannerExecutionResult failure = parseFailure(plannerResult, "Tool requests cannot set goal intents");
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
		}
		if (!isValidToolRequest(toolRequest)) {
			Airicraft.LOGGER.warn(
				"Planner returned invalid tool request type={} prompt={}",
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt())
			);
			PlannerExecutionResult failure = parseFailure(plannerResult, "Planner requested an invalid tool");
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
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

		sessionCoordinator.markToolWait(plannerResult.generation());
		pendingToolExecution = new PendingToolExecution(plannerResult.generation(), requestVisionTool(toolRequest));
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

	public void onAcceptedReplyRecorded() {
		awaitingAcceptedReplyRecord = false;
		if (
			pendingSubmitRequest != null
			&& contextAggregator.hasQueuedTriggers()
			&& !compactionService.hasInFlight()
			&& sessionCoordinator.activeGeneration() == 0L
			&& pendingToolExecution == null
		) {
			startQueuedWorkIfPossible();
		}
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
		cancelPendingTool();
		sessionCoordinator.reset();
		compactionService.reset();
		contextAggregator.clear();
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
	}

	public void shutdown() {
		cancelPendingTool();
		sessionCoordinator.shutdown();
		compactionService.shutdown();
		contextAggregator.clear();
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
	}

	private boolean startQueuedWorkIfPossible() {
		if (pendingSubmitRequest == null || !contextAggregator.hasQueuedTriggers()) {
			return true;
		}
		if (awaitingAcceptedReplyRecord) {
			return true;
		}
		if (contextAggregator.compactionPending()) {
			if (sessionCoordinator.hasInFlight() || pendingToolExecution != null) {
				return true;
			}
			if (compactionService.hasInFlight()) {
				return true;
			}
			return compactionService.submit(contextAggregator.buildCompactionConversation());
		}

		contextAggregator.dropSupersededGeneration(sessionCoordinator.contextSnapshotFor(sessionCoordinator.activeGeneration()));
		PlannerContextSnapshot snapshot = contextAggregator.freezePlannerSnapshot(pendingSubmitRequest);
		if (snapshot == null) {
			return true;
		}
		sessionCoordinator.submit(snapshot);
		return true;
	}

	private void acceptGeneration(PlannerExecutionResult acceptedResult) {
		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(acceptedResult.generation());
		if (snapshot != null) {
			contextAggregator.commitAcceptedTriggerBatch(snapshot);
		}
		sessionCoordinator.finishGeneration(acceptedResult.generation(), false);
		if (!contextAggregator.hasQueuedTriggers()) {
			pendingSubmitRequest = null;
			awaitingAcceptedReplyRecord = false;
		}
		else if (
			acceptedResult.response() == null
			|| acceptedResult.response().replyText() == null
			|| acceptedResult.response().replyText().isBlank()
		) {
			awaitingAcceptedReplyRecord = false;
			startQueuedWorkIfPossible();
		}
		else {
			awaitingAcceptedReplyRecord = true;
		}
	}

	private PlannerExecutionResult continueAfterTool() {
		PendingToolExecution toolExecution = pendingToolExecution;
		if (toolExecution == null || !toolExecution.future().isDone()) {
			return null;
		}

		ToolExecutionOutcome toolOutcome;
		try {
			toolOutcome = toolExecution.future().join();
		}
		catch (CompletionException exception) {
			toolOutcome = new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_failed");
			Airicraft.LOGGER.warn("Planner tool future failed generation={}", toolExecution.generation(), exception);
		}
		finally {
			pendingToolExecution = null;
			captureInFlight = false;
		}

		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(toolExecution.generation());
		if (snapshot == null) {
			return null;
		}

		PlannerRequest followUpRequest = snapshot.request().withToolResult(toolOutcome.toolResultText());
		sessionCoordinator.submitToolFollowUp(
			toolExecution.generation(),
			followUpRequest,
			toolOutcome.appendFollowUp(contextAggregator, snapshot)
		);
		return null;
	}

	private CompletableFuture<ToolExecutionOutcome> requestVisionTool(PlannerToolRequest toolRequest) {
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

	private void cancelPendingTool() {
		if (pendingToolExecution != null) {
			pendingToolExecution.future().cancel(true);
			pendingToolExecution = null;
		}
		captureInFlight = false;
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

	private PlannerExecutionResult parseFailure(PlannerExecutionResult baseResult, String message) {
		return new PlannerExecutionResult(
			baseResult.request(),
			null,
			LlmUsageSnapshot.unknown(),
			LlmFailureType.PARSE_ERROR,
			message,
			baseResult.generation(),
			baseResult.attempt(),
			baseResult.phase(),
			false
		);
	}

	private sealed interface ToolExecutionOutcome permits TextToolExecutionOutcome, ImageToolExecutionOutcome {
		String toolResultText();

		LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator, PlannerContextSnapshot snapshot);
	}

	private record TextToolExecutionOutcome(String toolResultText) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator, PlannerContextSnapshot snapshot) {
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, toolResultText);
		}
	}

	private record ImageToolExecutionOutcome(String toolResultText, LlmImageAttachment imageAttachment) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator, PlannerContextSnapshot snapshot) {
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, toolResultText, imageAttachment);
		}
	}

	private record PendingToolExecution(long generation, CompletableFuture<ToolExecutionOutcome> future) {
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
