package ai.moeru.airicraft.agent.llm;

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
	private final ExecutorService executorService;
	private final Map<Long, InFlightAttempt> inFlightAttempts = new LinkedHashMap<>();

	private long nextSubmissionId = 1L;

	public PlannerExecutor(LlmBackend llmBackend) {
		this.llmBackend = Objects.requireNonNull(llmBackend, "llmBackend");
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
		return submit(0L, 1, PlannerSessionPhase.PLANNER_REQUEST, request, conversation);
	}

	public boolean submit(long generation, int attempt, PlannerSessionPhase phase, PlannerRequest request, LlmConversation conversation) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(conversation, "conversation");

		long submissionId = nextSubmissionId++;
		CompletableFuture<LlmCallResult<PlannerResponse>> future = CompletableFuture.supplyAsync(() -> {
			try {
				return llmBackend.generate(conversation);
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		inFlightAttempts.put(submissionId, new InFlightAttempt(submissionId, generation, attempt, phase, request, future));
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

	public void reset() {
		for (InFlightAttempt attempt : inFlightAttempts.values()) {
			attempt.future().cancel(true);
		}
		inFlightAttempts.clear();
	}

	public void shutdown() {
		reset();
		executorService.shutdownNow();
	}

	private record InFlightAttempt(
		long submissionId,
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		CompletableFuture<LlmCallResult<PlannerResponse>> future
	) {
	}
}
