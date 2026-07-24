package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannerExecutor {
	private final LlmBackend llmBackend;
	private final AgentObservability observability;
	private final ExecutorService executorService;
	private final Map<Long, InFlightAttempt> inFlightAttempts = new LinkedHashMap<>();

	private long nextSubmissionId = 1L;

	public PlannerExecutor(LlmBackend llmBackend) {
		this(llmBackend, NoopObservability.INSTANCE);
	}

	public PlannerExecutor(LlmBackend llmBackend, AgentObservability observability) {
		this.llmBackend = Objects.requireNonNull(llmBackend, "llmBackend");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-planner");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean isConfigured() {
		return llmBackend.isConfigured();
	}

	public boolean hasInFlight() {
		return !inFlightAttempts.isEmpty();
	}

	public int activeAttemptCount() {
		return inFlightAttempts.size();
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, Context.current(), AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, parentContext, AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext, String spanName) {
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation, parentContext, spanName);
	}

	public boolean submit(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation) {
		return submit(generation, attempt, phase, request, conversation, Context.current(), AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation, Context parentContext, String spanName) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(conversation, "conversation");
		Objects.requireNonNull(spanName, "spanName");

		Context executionContext = parentContext == null ? Context.current() : parentContext;
		Context plannerContext = observability.startChildSpan(spanName, executionContext);
		long submissionId = nextSubmissionId++;
		CompletableFuture<LlmCallResult<PlannerResponse>> future = CompletableFuture.supplyAsync(() -> {
			try (Scope scope = plannerContext.makeCurrent()) {
				return llmBackend.generate(new PlannerBackendRequest(generation, attempt, phase, request, conversation));
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		inFlightAttempts.put(submissionId, new InFlightAttempt(submissionId, generation, attempt, phase, request, future, plannerContext));
		return true;
	}

	public PlannerExecutionResult poll() {
		Iterator<Map.Entry<Long, InFlightAttempt>> iterator = inFlightAttempts.entrySet().iterator();
		while (iterator.hasNext()) {
			InFlightAttempt attempt = iterator.next().getValue();
			if (!attempt.future().isDone()) {
				continue;
			}
			iterator.remove();
			endFlightSpan(attempt.context());
			Context failureContext = attempt.context() == null ? Context.current() : attempt.context();
			try {
				LlmCallResult<PlannerResponse> result = attempt.future().join();
				return new PlannerExecutionResult(
					attempt.request(),
					result.payload(),
					result.usage(),
					null,
					null,
					attempt.generation(),
					attempt.attempt(),
					attempt.phase(),
					false
				);
			}
			catch (CompletionException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof LlmBackendException backendException) {
					return new PlannerExecutionResult(
						attempt.request(),
						null,
						LlmUsageSnapshot.unknown(),
						backendException.failureType(),
						backendException.getMessage(),
						attempt.generation(),
						attempt.attempt(),
						attempt.phase(),
						false
					);
				}
				observability.recordFailure(
					failureContext,
					LlmFailureType.PROVIDER_ERROR.name(),
					cause == null ? exception.getMessage() : cause.getMessage(),
					cause instanceof Throwable throwable ? throwable : exception
				);
				return new PlannerExecutionResult(
					attempt.request(),
					null,
					LlmUsageSnapshot.unknown(),
					LlmFailureType.PROVIDER_ERROR,
					cause == null ? exception.getMessage() : cause.getMessage(),
					attempt.generation(),
					attempt.attempt(),
					attempt.phase(),
					false
				);
			}
		}
		return null;
	}

	public void injectMockResponse(PlannerResponse response) {
		llmBackend.injectMockResponse(response);
	}

	public void injectTimeout() {
		llmBackend.injectTimeout();
	}

	public boolean managesConversationHistory() {
		return llmBackend.managesConversationHistory();
	}

	public void acceptGeneration(long generation) throws LlmBackendException {
		llmBackend.acceptGeneration(generation);
	}

	public void discardGeneration(long generation) {
		llmBackend.discardGeneration(generation);
		if (!llmBackend.supportsGenerationCancellation()) {
			return;
		}
		Iterator<Map.Entry<Long, InFlightAttempt>> iterator = inFlightAttempts.entrySet().iterator();
		while (iterator.hasNext()) {
			InFlightAttempt attempt = iterator.next().getValue();
			if (attempt.generation() != generation) {
				continue;
			}
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
			iterator.remove();
		}
	}

	public void reset() {
		for (InFlightAttempt attempt : inFlightAttempts.values()) {
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
		}
		inFlightAttempts.clear();
		llmBackend.resetBackend();
	}

	public void shutdown() {
		for (InFlightAttempt attempt : inFlightAttempts.values()) {
			attempt.future().cancel(true);
			endFlightSpan(attempt.context());
		}
		inFlightAttempts.clear();
		llmBackend.shutdownBackend();
		executorService.shutdownNow();
	}

	private void endFlightSpan(Context context) {
		if (context != null) {
			observability.endSpan(context);
		}
	}

	private record InFlightAttempt(
		long submissionId,
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		CompletableFuture<LlmCallResult<PlannerResponse>> future,
		Context context
	) {
	}
}
