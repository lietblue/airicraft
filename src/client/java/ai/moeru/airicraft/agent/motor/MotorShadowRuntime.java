package ai.moeru.airicraft.agent.motor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Non-blocking, single-flight shadow observer for one recurrent Optimus-3 session.
 *
 * <p>This class has no Minecraft input or actuation dependency. A graph identity
 * change invalidates the old generation, closes its session, and creates exactly
 * one fresh session. A transport, timeout, or schema failure quarantines that
 * generation; it is never retried until the graph identity changes.</p>
 */
public final class MotorShadowRuntime implements AutoCloseable {
	private final Object lock = new Object();
	private final boolean enabled;
	private final MotorPolicyClient client;
	private final long createTimeoutMillis;
	private final long stepTimeoutMillis;
	private final long closeTimeoutMillis;
	private final long seed;
	private final Supplier<String> sessionIdSupplier;

	private MotorShadowStatus status;
	private boolean shutdownRequested;
	private boolean transportClosed;
	private long generation;
	private MotorGraphIdentity desiredIdentity;
	private MotorShadowObservation pendingObservation;
	private MotorPolicySession activeSession;
	private boolean activeSessionValidated;
	private boolean activeSessionRetired;
	private final ArrayDeque<MotorShadowTransition> transitions = new ArrayDeque<>();
	private CompletableFuture<?> inFlight;
	private Operation inFlightOperation;
	private boolean generationFaulted;
	private long nextStepIndex;
	private MotorPolicyStepResult lastResult;
	private String lastFailureCode;

	private long observations;
	private long sessionCreates;
	private long validatedSessions;
	private long stepRequests;
	private long sessionCloses;
	private long completedSteps;
	private long backpressuredObservations;
	private long droppedObservations;
	private long staleResults;
	private long failures;
	private long timeouts;
	private long suppressedAfterFailure;

	private MotorShadowRuntime(
		boolean enabled,
		MotorPolicyClient client,
		Duration createTimeout,
		Duration stepTimeout,
		Duration closeTimeout,
		long seed,
		Supplier<String> sessionIdSupplier
	) {
		this.enabled = enabled;
		this.client = client;
		this.createTimeoutMillis = createTimeout == null ? 0L : positiveTimeoutMillis(createTimeout, "createTimeout");
		this.stepTimeoutMillis = stepTimeout == null ? 0L : positiveTimeoutMillis(stepTimeout, "stepTimeout");
		this.closeTimeoutMillis = closeTimeout == null ? 0L : positiveTimeoutMillis(closeTimeout, "closeTimeout");
		this.seed = enabled ? MotorPolicyContract.requireUint32Seed(seed) : seed;
		this.sessionIdSupplier = sessionIdSupplier;
		this.status = enabled ? MotorShadowStatus.IDLE : MotorShadowStatus.DISABLED;
	}

	public static MotorShadowRuntime enabled(MotorPolicyClient client, Duration timeout, long seed) {
		return enabled(client, timeout, timeout, timeout, seed);
	}

	public static MotorShadowRuntime enabled(
		MotorPolicyClient client,
		Duration createTimeout,
		Duration stepTimeout,
		long seed
	) {
		return enabled(client, createTimeout, stepTimeout, createTimeout, seed);
	}

	public static MotorShadowRuntime enabled(
		MotorPolicyClient client,
		Duration createTimeout,
		Duration stepTimeout,
		Duration closeTimeout,
		long seed
	) {
		return new MotorShadowRuntime(
			true,
			Objects.requireNonNull(client, "client"),
			createTimeout,
			stepTimeout,
			closeTimeout,
			seed,
			() -> UUID.randomUUID().toString()
		);
	}

	public static MotorShadowRuntime enabled(MotorPolicyClient client, Duration timeout) {
		return enabled(client, timeout, 0L);
	}

	public static MotorShadowRuntime disabled() {
		return new MotorShadowRuntime(false, null, null, null, null, 0L, null);
	}

	static MotorShadowRuntime enabled(
		MotorPolicyClient client,
		Duration timeout,
		long seed,
		Supplier<String> sessionIdSupplier
	) {
		return enabled(client, timeout, timeout, timeout, seed, sessionIdSupplier);
	}

	static MotorShadowRuntime enabled(
		MotorPolicyClient client,
		Duration createTimeout,
		Duration stepTimeout,
		long seed,
		Supplier<String> sessionIdSupplier
	) {
		return enabled(client, createTimeout, stepTimeout, createTimeout, seed, sessionIdSupplier);
	}

	static MotorShadowRuntime enabled(
		MotorPolicyClient client,
		Duration createTimeout,
		Duration stepTimeout,
		Duration closeTimeout,
		long seed,
		Supplier<String> sessionIdSupplier
	) {
		return new MotorShadowRuntime(
			true,
			Objects.requireNonNull(client, "client"),
			createTimeout,
			stepTimeout,
			closeTimeout,
			seed,
			Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier")
		);
	}

	/** Offers one observation without waiting for capture, network, or inference. */
	public void observe(MotorShadowObservation observation) {
		Objects.requireNonNull(observation, "observation");
		synchronized (lock) {
			if (!enabled || shutdownRequested) {
				return;
			}
			observations++;
			boolean identityChanged = !observation.identity().equals(desiredIdentity);
			if (identityChanged) {
				retireActiveSession("identity_changed");
				advanceGeneration();
				desiredIdentity = observation.identity();
				generationFaulted = false;
				nextStepIndex = 0L;
				lastResult = null;
				lastFailureCode = null;
				if (pendingObservation != null) {
					droppedObservations++;
				}
				pendingObservation = observation;
			}
			else if (generationFaulted) {
				suppressedAfterFailure++;
				return;
			}
			else {
				if (inFlight != null) {
					backpressuredObservations++;
				}
				if (pendingObservation != null) {
					droppedObservations++;
				}
				pendingObservation = observation;
			}
			reconcile();
		}
	}

	/** Invalidates the current generation and asynchronously closes its session. */
	public void reset() {
		reset("reset");
	}

	public void reset(String reason) {
		synchronized (lock) {
			if (!enabled || shutdownRequested) {
				return;
			}
			retireActiveSession(normalizedReason(reason));
			advanceGeneration();
			desiredIdentity = null;
			pendingObservation = null;
			generationFaulted = false;
			nextStepIndex = 0L;
			lastResult = null;
			lastFailureCode = null;
			reconcile();
		}
	}

	public List<MotorShadowTransition> drainTransitions() {
		synchronized (lock) {
			ArrayList<MotorShadowTransition> drained = new ArrayList<>(transitions);
			transitions.clear();
			return List.copyOf(drained);
		}
	}

	public MotorShadowSnapshot snapshot() {
		synchronized (lock) {
			if (!enabled) {
				if (status == MotorShadowStatus.CLOSED) {
					MotorShadowSnapshot disabled = MotorShadowSnapshot.disabled();
					return new MotorShadowSnapshot(
						false, MotorShadowStatus.CLOSED, disabled.generation(), null, null, 0,
						false, null, null, null,
						0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null
					);
				}
				return MotorShadowSnapshot.disabled();
			}
			String sessionId = activeSession != null
				&& activeSessionValidated
				&& !activeSessionRetired
				&& !generationFaulted
				&& activeSession.generation() == generation
				&& activeSession.identity().equals(desiredIdentity)
				? activeSession.sessionId()
				: null;
			return new MotorShadowSnapshot(
				true,
				status,
				generation,
				desiredIdentity,
				sessionId,
				nextStepIndex,
				inFlight != null,
				inFlightOperation == null ? null : inFlightOperation.wireName,
				pendingObservation == null ? null : pendingObservation.frame().snapshot(),
				lastResult,
				observations,
				sessionCreates,
				validatedSessions,
				stepRequests,
				sessionCloses,
				completedSteps,
				backpressuredObservations,
				droppedObservations,
				staleResults,
				failures,
				timeouts,
				suppressedAfterFailure,
				lastFailureCode
			);
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (!enabled) {
				status = MotorShadowStatus.CLOSED;
				return;
			}
			if (shutdownRequested) {
				return;
			}
			shutdownRequested = true;
			retireActiveSession("runtime_shutdown");
			advanceGeneration();
			desiredIdentity = null;
			pendingObservation = null;
			generationFaulted = false;
			reconcile();
		}
	}

	private void reconcile() {
		if (inFlight != null) {
			return;
		}
		if (activeSession != null && (
			shutdownRequested
				|| desiredIdentity == null
				|| activeSession.generation() != generation
				|| generationFaulted
		)) {
			dispatchClose(activeSession);
			return;
		}
		if (shutdownRequested) {
			closeTransport();
			return;
		}
		if (desiredIdentity == null) {
			status = MotorShadowStatus.IDLE;
			return;
		}
		if (generationFaulted) {
			status = MotorShadowStatus.FAILED;
			return;
		}
		if (activeSession == null) {
			dispatchCreate();
			return;
		}
		if (pendingObservation != null) {
			dispatchStep();
			return;
		}
		status = MotorShadowStatus.READY;
	}

	private void dispatchCreate() {
		String sessionId;
		try {
			sessionId = MotorPolicyContract.requireSessionId(sessionIdSupplier.get());
		}
		catch (RuntimeException exception) {
			recordFailure(exception);
			return;
		}
		MotorSessionCreateRequest request = MotorSessionCreateRequest.create(
			desiredIdentity,
			sessionId,
			generation,
			seed
		);
		sessionCreates++;
		status = MotorShadowStatus.CREATING_SESSION;
		CompletableFuture<MotorPolicySession> future;
		try {
			future = requireFuture(client.createSession(request));
		}
		catch (RuntimeException exception) {
			activeSession = syntheticSession(request);
			activeSessionValidated = false;
			activeSessionRetired = true;
			recordFailure(exception);
			return;
		}
		start(Operation.CREATE, future, (result, failure) -> completeCreate(request, result, failure));
	}

	private void completeCreate(MotorSessionCreateRequest request, MotorPolicySession result, Throwable failure) {
		boolean stale = request.generation() != generation || !request.identity().equals(desiredIdentity);
		if (stale) {
			staleResults++;
			if (failure == null && sessionMatches(result, request)) {
				activeSession = result;
				activeSessionValidated = true;
				activeSessionRetired = true;
			}
			else {
				activeSession = syntheticSession(request);
				activeSessionValidated = false;
				activeSessionRetired = true;
			}
			reconcile();
			return;
		}
		if (failure != null) {
			activeSession = syntheticSession(request);
			activeSessionValidated = false;
			activeSessionRetired = true;
			recordFailure(failure);
			return;
		}
		if (!sessionMatches(result, request)) {
			activeSession = syntheticSession(request);
			activeSessionValidated = false;
			activeSessionRetired = true;
			recordFailure(MotorPolicyException.schema("session result does not echo its request"));
			return;
		}
		activeSession = result;
		activeSessionValidated = true;
		activeSessionRetired = false;
		validatedSessions++;
		transitions.add(new MotorShadowTransition(
			MotorShadowTransition.Type.SESSION_VALIDATED,
			result.identity(),
			result.generation(),
			result.sessionId(),
			result.recurrentResetCount(),
			null,
			"validated",
			true
		));
		status = MotorShadowStatus.READY;
		reconcile();
	}

	private void dispatchStep() {
		MotorShadowObservation observation = pendingObservation;
		pendingObservation = null;
		long stepIndex = nextStepIndex++;
		MotorPolicyStepRequest request = new MotorPolicyStepRequest(
			activeSession.identity(),
			activeSession.sessionId(),
			activeSession.generation(),
			stepIndex,
			activeSession.seed(),
			activeSession.prompt(),
			observation.minecraftTick(),
			observation.frame(),
			false
		);
		stepRequests++;
		status = MotorShadowStatus.STEPPING;
		CompletableFuture<MotorPolicyStepResult> future;
		try {
			future = requireFuture(client.step(request));
		}
		catch (RuntimeException exception) {
			recordFailure(exception);
			return;
		}
		start(Operation.STEP, future, (result, failure) -> completeStep(request, result, failure));
	}

	private void completeStep(MotorPolicyStepRequest request, MotorPolicyStepResult result, Throwable failure) {
		boolean stale = request.generation() != generation || !request.identity().equals(desiredIdentity);
		if (stale) {
			staleResults++;
			reconcile();
			return;
		}
		if (failure != null) {
			recordFailure(failure);
			return;
		}
		if (!stepMatches(result, request)) {
			recordFailure(MotorPolicyException.schema("step result does not echo its request"));
			return;
		}
		lastResult = result;
		completedSteps++;
		transitions.add(new MotorShadowTransition(
			MotorShadowTransition.Type.STEP_COMPLETED,
			result.identity(),
			result.generation(),
			result.sessionId(),
			1,
			result,
			"completed",
			true
		));
		status = MotorShadowStatus.READY;
		reconcile();
	}

	private void dispatchClose(MotorPolicySession session) {
		MotorSessionCloseRequest request = new MotorSessionCloseRequest(session);
		sessionCloses++;
		status = MotorShadowStatus.CLOSING_SESSION;
		CompletableFuture<Void> future;
		try {
			future = requireFuture(client.closeSession(request));
		}
		catch (RuntimeException exception) {
			activeSession = null;
			activeSessionValidated = false;
			activeSessionRetired = false;
			recordCloseFailure(session, exception);
			recordSessionClosed(session, false);
			reconcile();
			return;
		}
		start(Operation.CLOSE, future, (ignored, failure) -> {
			if (failure != null) {
				recordCloseFailure(session, failure);
			}
			recordSessionClosed(session, failure == null);
			if (activeSession != null && activeSession.sessionId().equals(session.sessionId())) {
				activeSession = null;
				activeSessionValidated = false;
				activeSessionRetired = false;
			}
			reconcile();
		});
	}

	private <T> void start(Operation operation, CompletableFuture<T> source, Completion<T> completion) {
		CompletableFuture<T> timed = new CompletableFuture<>();
		inFlight = timed;
		inFlightOperation = operation;
		source.whenComplete((result, failure) -> {
			if (failure == null) {
				timed.complete(result);
			}
			else {
				timed.completeExceptionally(failure);
			}
		});
		timed.orTimeout(timeoutMillis(operation), TimeUnit.MILLISECONDS);
		timed.whenComplete((result, failure) -> {
			synchronized (lock) {
				if (inFlight != timed) {
					return;
				}
				Throwable unwrapped = unwrap(failure);
				if (unwrapped instanceof TimeoutException) {
					source.cancel(true);
				}
				inFlight = null;
				inFlightOperation = null;
				completion.complete(result, unwrapped);
			}
		});
	}

	private void recordFailure(Throwable failure) {
		Throwable unwrapped = unwrap(failure);
		generationFaulted = true;
		pendingObservation = null;
		failures++;
		if (unwrapped instanceof TimeoutException || isPolicyTimeout(unwrapped)) {
			timeouts++;
			lastFailureCode = "timeout";
		}
		else if (unwrapped instanceof MotorPolicyException policyException) {
			lastFailureCode = policyException.code();
		}
		else {
			lastFailureCode = "unexpected_failure";
		}
		transitions.add(failureTransition(
			desiredIdentity,
			generation,
			activeSession == null ? "" : activeSession.sessionId(),
			lastFailureCode
		));
		retireActiveSession("failure_" + lastFailureCode);
		status = MotorShadowStatus.FAILED;
		reconcile();
	}

	private void recordCloseFailure(MotorPolicySession session, Throwable failure) {
		Throwable unwrapped = unwrap(failure);
		failures++;
		if (unwrapped instanceof TimeoutException || isPolicyTimeout(unwrapped)) {
			timeouts++;
			lastFailureCode = "close_timeout";
		}
		else if (unwrapped instanceof MotorPolicyException policyException) {
			lastFailureCode = "close_" + policyException.code();
		}
		else {
			lastFailureCode = "close_failure";
		}
		transitions.add(failureTransition(
			session.identity(),
			session.generation(),
			session.sessionId(),
			lastFailureCode
		));
	}

	private void retireActiveSession(String reason) {
		if (activeSession == null || !activeSessionValidated || activeSessionRetired) {
			return;
		}
		activeSessionRetired = true;
		transitions.add(new MotorShadowTransition(
			MotorShadowTransition.Type.SESSION_RETIRED,
			activeSession.identity(),
			activeSession.generation(),
			activeSession.sessionId(),
			activeSession.recurrentResetCount(),
			null,
			normalizedReason(reason),
			true
		));
	}

	private void recordSessionClosed(MotorPolicySession session, boolean successful) {
		transitions.add(new MotorShadowTransition(
			MotorShadowTransition.Type.SESSION_CLOSED,
			session.identity(),
			session.generation(),
			session.sessionId(),
			session.recurrentResetCount(),
			null,
			successful ? "closed" : "close_failed",
			successful
		));
	}

	private static MotorShadowTransition failureTransition(
		MotorGraphIdentity identity,
		long generation,
		String sessionId,
		String code
	) {
		return new MotorShadowTransition(
			MotorShadowTransition.Type.FAILURE,
			identity,
			generation,
			sessionId == null ? "" : sessionId,
			0,
			null,
			code,
			false
		);
	}

	private void closeTransport() {
		if (transportClosed) {
			status = MotorShadowStatus.CLOSED;
			return;
		}
		transportClosed = true;
		try {
			client.close();
		}
		catch (RuntimeException exception) {
			failures++;
			lastFailureCode = "transport_close_failure";
		}
		status = MotorShadowStatus.CLOSED;
	}

	private void advanceGeneration() {
		if (generation == Long.MAX_VALUE) {
			throw new IllegalStateException("Motor shadow generation sequence is exhausted");
		}
		generation++;
	}

	private static boolean sessionMatches(MotorPolicySession result, MotorSessionCreateRequest request) {
		return result != null
			&& result.identity().equals(request.identity())
			&& result.sessionId().equals(request.sessionId())
			&& result.generation() == request.generation()
			&& result.seed() == request.seed()
			&& result.prompt().equals(request.prompt());
	}

	private static MotorPolicySession syntheticSession(MotorSessionCreateRequest request) {
		return new MotorPolicySession(
			request.identity(),
			request.sessionId(),
			request.generation(),
			request.seed(),
			request.prompt(),
			1,
			java.time.Instant.EPOCH
		);
	}

	private static boolean stepMatches(MotorPolicyStepResult result, MotorPolicyStepRequest request) {
		return result != null
			&& result.identity().equals(request.identity())
			&& result.sessionId().equals(request.sessionId())
			&& result.generation() == request.generation()
			&& result.stepIndex() == request.stepIndex()
			&& result.seed() == request.seed()
			&& result.prompt().equals(request.prompt())
			&& result.minecraftTick() == request.minecraftTick()
			&& result.frameId() == request.frame().frameId()
			&& result.capturedAtMs() == request.frame().capturedAtMs()
			&& result.encodedFrameSha256().equals(request.frame().encodedSha256())
			&& result.decodedPixelsSha256().equals(request.frame().decodedRgbSha256())
			&& !result.actuationAuthorized();
	}

	private long timeoutMillis(Operation operation) {
		return switch (operation) {
			case CREATE -> createTimeoutMillis;
			case STEP -> stepTimeoutMillis;
			case CLOSE -> closeTimeoutMillis;
		};
	}

	private static long positiveTimeoutMillis(Duration timeout, String name) {
		Objects.requireNonNull(timeout, name);
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		long millis;
		try {
			millis = timeout.toMillis();
		}
		catch (ArithmeticException exception) {
			throw new IllegalArgumentException(name + " is too large", exception);
		}
		if (millis <= 0) {
			throw new IllegalArgumentException(name + " must be at least one millisecond");
		}
		return millis;
	}

	private static <T> CompletableFuture<T> requireFuture(CompletableFuture<T> future) {
		return Objects.requireNonNull(future, "MotorPolicyClient returned a null future");
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static boolean isPolicyTimeout(Throwable failure) {
		return failure instanceof MotorPolicyException policyException
			&& "timeout".equals(policyException.code());
	}

	private static String normalizedReason(String reason) {
		return reason == null || reason.isBlank() ? "reset" : reason;
	}

	private enum Operation {
		CREATE("create_session"),
		STEP("step"),
		CLOSE("close_session");

		private final String wireName;

		Operation(String wireName) {
			this.wireName = wireName;
		}
	}

	@FunctionalInterface
	private interface Completion<T> {
		void complete(T result, Throwable failure);
	}
}
