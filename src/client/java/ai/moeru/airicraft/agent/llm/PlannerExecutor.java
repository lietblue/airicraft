package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannerExecutor {
	private final LlmBackend llmBackend;
	private final AgentObservability observability;
	private final ExecutorService executorService;

	private CompletableFuture<LlmCallResult<PlannerResponse>> inFlight;
	private Context inFlightContext;
	private PlannerRequest inFlightRequest;

	public PlannerExecutor(LlmBackend llmBackend) {
		this(llmBackend, NoopObservability.INSTANCE);
	}

	public PlannerExecutor(LlmBackend llmBackend, AgentObservability observability) {
		this.llmBackend = Objects.requireNonNull(llmBackend, "llmBackend");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-planner");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean isConfigured() {
		return llmBackend.isConfigured();
	}

	public boolean hasInFlight() {
		return inFlight != null;
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation) {
		return submit(request, conversation, Context.current());
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext) {
		return submit(request, conversation, parentContext, AgentObservability.PLANNER_REQUEST_SPAN_NAME);
	}

	public boolean submit(PlannerRequest request, LlmConversation conversation, Context parentContext, String spanName) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(conversation, "conversation");
		Objects.requireNonNull(spanName, "spanName");
		if (inFlight != null) {
			return false;
		}

		Context executionContext = parentContext == null ? Context.current() : parentContext;
		Context plannerContext = observability.startChildSpan(spanName, executionContext);
		inFlightRequest = request;
		inFlight = CompletableFuture.supplyAsync(() -> {
			try (Scope scope = plannerContext.makeCurrent()) {
				return llmBackend.generate(conversation);
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		inFlightContext = plannerContext;
		return true;
	}

	public PlannerExecutionResult poll() {
		if (inFlight == null || !inFlight.isDone()) {
			return null;
		}

		PlannerRequest request = inFlightRequest;
		CompletableFuture<LlmCallResult<PlannerResponse>> completedFuture = inFlight;
		inFlight = null;
		inFlightRequest = null;
		Context completedContext = inFlightContext;
		inFlightContext = null;
		endCurrentFlightSpan(completedContext);
		Context failureContext = completedContext == null ? Context.current() : completedContext;

		try {
			LlmCallResult<PlannerResponse> result = completedFuture.join();
			return new PlannerExecutionResult(request, result.payload(), result.usage(), null, null);
		}
		catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof LlmBackendException backendException) {
				return new PlannerExecutionResult(request, null, LlmUsageSnapshot.unknown(), backendException.failureType(), backendException.getMessage());
			}
			observability.recordFailure(
				failureContext,
				LlmFailureType.PROVIDER_ERROR.name(),
				cause == null ? exception.getMessage() : cause.getMessage(),
				cause instanceof Throwable throwable ? throwable : exception
			);
			return new PlannerExecutionResult(
				request,
				null,
				LlmUsageSnapshot.unknown(),
				LlmFailureType.PROVIDER_ERROR,
				cause == null ? exception.getMessage() : cause.getMessage()
			);
		}
	}

	public void injectMockResponse(PlannerResponse response) {
		llmBackend.injectMockResponse(response);
	}

	public void injectTimeout() {
		llmBackend.injectTimeout();
	}

	public void reset() {
		if (inFlight != null) {
			inFlight.cancel(true);
			inFlight = null;
			inFlightRequest = null;
			endCurrentFlightSpan(inFlightContext);
			inFlightContext = null;
		}
	}

	public void shutdown() {
		reset();
		executorService.shutdownNow();
	}

	private void endCurrentFlightSpan(Context context) {
		if (context != null) {
			observability.endSpan(context);
		}
	}
}
