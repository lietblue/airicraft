package ai.moeru.airicraft.agent.motor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-thread coordinator for capture, inference, and raw-only evidence.
 *
 * <p>The only collaborators are a framebuffer reader and a shadow policy
 * observer. Neither exposes Minecraft input controls.</p>
 */
public final class Optimus3MotorShadowCoordinator implements AutoCloseable {
	private static final String EVENT_PREFIX = "motor.optimus3_shadow.";

	private final Object lock = new Object();
	private final MotorFrameSource frameCapture;
	private final MotorShadowRuntime runtime;
	private final long deadlineMillis;
	private final ConcurrentLinkedQueue<MotorShadowEvent> events = new ConcurrentLinkedQueue<>();

	private MotorGraphIdentity eligibleIdentity;
	private CompletableFuture<MotorFrame> captureFuture;
	private long captureTick;
	private MotorGraphIdentity captureFaultedIdentity;
	private String captureFailureCode = "";
	private long captureFailures;
	private String lastSkipReason = "";
	private boolean closed;

	public Optimus3MotorShadowCoordinator(
		MotorFrameSource frameCapture,
		MotorShadowRuntime runtime,
		Duration deadline
	) {
		this.frameCapture = Objects.requireNonNull(frameCapture, "frameCapture");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(deadline, "deadline");
		if (deadline.isZero() || deadline.isNegative()) {
			throw new IllegalArgumentException("deadline must be positive");
		}
		this.deadlineMillis = Math.max(1L, deadline.toMillis());
	}

	public static Optimus3MotorShadowCoordinator disabled(MotorFrameSource frameCapture) {
		return new Optimus3MotorShadowCoordinator(frameCapture, MotorShadowRuntime.disabled(), Duration.ofMillis(1));
	}

	/** Called once per client tick immediately before the deterministic executor. */
	public void tick(MotorShadowEligibilityDecision decision, long minecraftTick) {
		Objects.requireNonNull(decision, "decision");
		drainRuntimeTransitions();
		synchronized (lock) {
			if (closed) {
				return;
			}
			if (!runtime.snapshot().enabled()) {
				lastSkipReason = normalizedReason(decision.reason());
				return;
			}
			if (!decision.eligible()) {
				stopEligibleIdentity(decision.reason());
				return;
			}
			lastSkipReason = "";
			MotorGraphIdentity nextIdentity = decision.identity();
			if (!nextIdentity.equals(eligibleIdentity)) {
				if (eligibleIdentity != null) {
					cancelCapture();
					runtime.reset("identity_changed");
					drainRuntimeTransitions();
				}
				eligibleIdentity = nextIdentity;
				captureFaultedIdentity = null;
				captureFailureCode = "";
			}
			MotorShadowSnapshot snapshot = runtime.snapshot();
			if (captureFuture == null && canCapture(snapshot, nextIdentity)) {
				requestFrame(nextIdentity, minecraftTick);
			}
		}
	}

	public void reset(String reason) {
		drainRuntimeTransitions();
		synchronized (lock) {
			eligibleIdentity = null;
			captureFaultedIdentity = null;
			captureFailureCode = "";
			lastSkipReason = "";
			cancelCapture();
			runtime.reset(normalizedReason(reason));
			drainRuntimeTransitions();
		}
	}

	public List<MotorShadowEvent> drainEvents() {
		ArrayList<MotorShadowEvent> drained = new ArrayList<>();
		for (MotorShadowEvent event = events.poll(); event != null; event = events.poll()) {
			drained.add(event);
		}
		return List.copyOf(drained);
	}

	public Map<String, Object> snapshotPayload() {
		MotorShadowSnapshot snapshot = runtime.snapshot();
		MotorGraphIdentity identity;
		String eligibilityReason;
		String localCaptureFailureCode;
		long localCaptureFailures;
		boolean captureFaulted;
		synchronized (lock) {
			identity = eligibleIdentity;
			eligibilityReason = identity == null ? lastSkipReason : "eligible";
			captureFaulted = identity != null && identity.equals(captureFaultedIdentity);
			localCaptureFailureCode = captureFailureCode;
			localCaptureFailures = captureFailures;
		}
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("enabled", snapshot.enabled());
		payload.put("mode", "shadow");
		payload.put("status", captureFaulted ? MotorShadowStatus.FAILED.name() : snapshot.status().name());
		payload.put("actuationAuthorized", false);
		payload.put("actuationApplied", false);
		payload.put("generation", snapshot.generation());
		payload.put("sessionId", snapshot.sessionId() == null ? "" : snapshot.sessionId());
		payload.put("nextStepIndex", snapshot.nextStepIndex());
		payload.put("requestInFlight", snapshot.requestInFlight());
		payload.put("inFlightOperation", snapshot.inFlightOperation() == null ? "" : snapshot.inFlightOperation());
		payload.put("eligible", identity != null);
		payload.put("eligibilityReason", eligibilityReason);
		if (identity != null) {
			payload.put("identity", identityPayload(identity));
		}
		payload.put("capture", frameCapture.snapshot());
		payload.put("observations", snapshot.observations());
		payload.put("sessionCreates", snapshot.sessionCreates());
		payload.put("validatedSessions", snapshot.validatedSessions());
		payload.put("sessionCloses", snapshot.sessionCloses());
		payload.put("stepRequests", snapshot.stepRequests());
		payload.put("completedSteps", snapshot.completedSteps());
		payload.put("backpressuredObservations", snapshot.backpressuredObservations());
		payload.put("droppedObservations", snapshot.droppedObservations());
		payload.put("staleResults", snapshot.staleResults());
		payload.put("failures", snapshot.failures() + localCaptureFailures);
		payload.put("captureFailures", localCaptureFailures);
		payload.put("timeouts", snapshot.timeouts());
		payload.put("suppressedAfterFailure", snapshot.suppressedAfterFailure());
		payload.put("lastFailureCode", captureFaulted
			? localCaptureFailureCode
			: snapshot.lastFailureCode() == null ? "" : snapshot.lastFailureCode());
		if (snapshot.lastResult() != null) {
			payload.put("lastStep", stepPayload(snapshot.lastResult()));
		}
		return java.util.Collections.unmodifiableMap(payload);
	}

	/**
	 * Polls asynchronous cleanup without blocking the client thread.
	 *
	 * <p>Calling this method also promotes completed close transitions into the
	 * coordinator event queue so the evaluator can persist the terminal close
	 * outcome before declaring cleanup complete.</p>
	 */
	public boolean cleanupComplete() {
		synchronized (lock) {
			MotorShadowSnapshot snapshot = runtime.snapshot();
			// Snapshot first. If a close completed before this snapshot, its
			// transition was queued under the same runtime lock and this drain
			// promotes it before a terminal result can be returned.
			drainRuntimeTransitions();
			return captureFuture == null
				&& !snapshot.requestInFlight()
				&& snapshot.pendingFrame() == null
				&& snapshot.sessionId() == null
				&& (
					snapshot.status() == MotorShadowStatus.IDLE
						|| snapshot.status() == MotorShadowStatus.DISABLED
						|| snapshot.status() == MotorShadowStatus.CLOSED
				);
		}
	}

	@Override
	public void close() {
		drainRuntimeTransitions();
		synchronized (lock) {
			if (closed) {
				return;
			}
			closed = true;
			cancelCapture();
			eligibleIdentity = null;
			captureFaultedIdentity = null;
			captureFailureCode = "";
			runtime.close();
			drainRuntimeTransitions();
		}
	}

	private void requestFrame(MotorGraphIdentity identity, long minecraftTick) {
		CompletableFuture<MotorFrame> requested;
		try {
			requested = frameCapture.requestCapture();
		}
		catch (RuntimeException exception) {
			recordCaptureFailure(identity, "capture_dispatch_failed");
			return;
		}
		captureFuture = requested;
		captureTick = minecraftTick;
		requested.whenComplete((frame, failure) -> completeCapture(requested, identity, frame, failure));
	}

	private void completeCapture(
		CompletableFuture<MotorFrame> requested,
		MotorGraphIdentity identity,
		MotorFrame frame,
		Throwable failure
	) {
		long observedTick;
		synchronized (lock) {
			if (captureFuture != requested) {
				return;
			}
			captureFuture = null;
			observedTick = captureTick;
			if (closed || !identity.equals(eligibleIdentity)) {
				return;
			}
		}
		if (failure != null || frame == null) {
			recordCaptureFailure(identity, failure instanceof java.util.concurrent.CancellationException
				? "capture_cancelled"
				: captureFailureCode(failure));
			return;
		}
		runtime.observe(new MotorShadowObservation(identity, observedTick, frame));
	}

	private void stopEligibleIdentity(String reason) {
		if (eligibleIdentity != null) {
			eligibleIdentity = null;
			captureFaultedIdentity = null;
			captureFailureCode = "";
			cancelCapture();
			runtime.reset(normalizedReason(reason));
			drainRuntimeTransitions();
		}
		String normalized = normalizedReason(reason);
		if (!normalized.equals(lastSkipReason)) {
			lastSkipReason = normalized;
			events.add(new MotorShadowEvent(
				EVENT_PREFIX + "skipped",
				Map.of("reason", normalized, "actuationAuthorized", false, "actuationApplied", false)
			));
		}
	}

	private void cancelCapture() {
		CompletableFuture<MotorFrame> pending = captureFuture;
		captureFuture = null;
		if (pending != null) {
			frameCapture.cancelActiveCapture();
			pending.cancel(false);
		}
	}

	private boolean canCapture(MotorShadowSnapshot snapshot, MotorGraphIdentity identity) {
		return snapshot.enabled()
			&& !identity.equals(captureFaultedIdentity)
			&& !snapshot.requestInFlight()
			&& snapshot.pendingFrame() == null
			&& snapshot.status() != MotorShadowStatus.FAILED
			&& snapshot.status() != MotorShadowStatus.CLOSED
			&& snapshot.status() != MotorShadowStatus.CLOSING_SESSION;
	}

	private void drainRuntimeTransitions() {
		for (MotorShadowTransition transition : runtime.drainTransitions()) {
			LinkedHashMap<String, Object> payload = transition.identity() == null
				? new LinkedHashMap<>()
				: shadowIdentityPayload(transition.identity());
			payload.putIfAbsent("actuationAuthorized", false);
			payload.putIfAbsent("actuationApplied", false);
			payload.put("generation", transition.generation());
			if (transition.sessionId() != null && !transition.sessionId().isBlank()) {
				payload.put("sessionId", transition.sessionId());
			}
			switch (transition.type()) {
				case SESSION_VALIDATED -> {
					payload.put("recurrentResetCount", transition.recurrentResetCount());
					events.add(new MotorShadowEvent(EVENT_PREFIX + "session_started", payload));
				}
				case STEP_COMPLETED -> events.add(new MotorShadowEvent(
					EVENT_PREFIX + "step",
					stepPayload(transition.stepResult())
				));
				case FAILURE -> {
					MotorShadowSnapshot snapshot = runtime.snapshot();
					payload.put("failureCode", transition.reason());
					payload.put("failureCount", snapshot.failures());
					payload.put("timeoutCount", snapshot.timeouts());
					events.add(new MotorShadowEvent(EVENT_PREFIX + "failed", payload));
				}
				case SESSION_RETIRED -> {
					payload.put("reason", transition.reason());
					events.add(new MotorShadowEvent(EVENT_PREFIX + "session_stopped", payload));
				}
				case SESSION_CLOSED -> {
					payload.put("closeSucceeded", transition.successful());
					events.add(new MotorShadowEvent(EVENT_PREFIX + "session_closed", payload));
				}
			}
		}
	}

	private void recordCaptureFailure(MotorGraphIdentity identity, String code) {
		long failureCount;
		synchronized (lock) {
			if (closed || !identity.equals(eligibleIdentity) || identity.equals(captureFaultedIdentity)) {
				return;
			}
			captureFaultedIdentity = identity;
			captureFailureCode = code;
			captureFailures++;
			failureCount = captureFailures;
		}
		LinkedHashMap<String, Object> payload = shadowIdentityPayload(identity);
		payload.put("failureCode", code);
		payload.put("failureCount", failureCount);
		events.add(new MotorShadowEvent(EVENT_PREFIX + "failed", payload));
	}

	private Map<String, Object> stepPayload(MotorPolicyStepResult result) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>(identityPayload(result.identity()));
		payload.put("sessionId", result.sessionId());
		payload.put("generation", result.generation());
		payload.put("stepIndex", result.stepIndex());
		payload.put("minecraftTick", result.minecraftTick());
		payload.put("frameId", result.frameId());
		payload.put("capturedAtMs", result.capturedAtMs());
		payload.put("encodedFrameSha256", result.encodedFrameSha256());
		payload.put("decodedPixelsSha256", result.decodedPixelsSha256());
		payload.put("rawAction", result.evidence().rawAction().toJson());
		payload.put("safeShadowAction", result.evidence().safeShadowAction().toJson());
		payload.put("forbiddenAttempts", result.evidence().forbiddenAttempts());
		payload.put("attackStabilizedControls", result.evidence().attackStabilizedControls());
		payload.put("serviceTiming", result.serviceTiming());
		double endToEndLatencyMs = result.endToEndLatencyNanos() / 1_000_000.0d;
		payload.put("endToEndLatencyMs", endToEndLatencyMs);
		payload.put("deadlineMillis", deadlineMillis);
		payload.put("deadlineMiss", result.endToEndLatencyNanos() > deadlineMillis * 1_000_000L);
		payload.put("actuationAuthorized", false);
		payload.put("actuationApplied", false);
		return java.util.Collections.unmodifiableMap(payload);
	}

	private static Map<String, Object> identityPayload(MotorGraphIdentity identity) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("executionId", identity.executionId());
		payload.put("graphActionId", identity.graphActionId());
		payload.put("graphStepId", identity.graphStepId());
		payload.put("graphPrimitive", identity.graphPrimitive());
		payload.put("stepAttempt", identity.stepAttempt());
		payload.put("taskId", identity.taskId());
		payload.put("taskType", identity.taskType());
		return payload;
	}

	private static LinkedHashMap<String, Object> shadowIdentityPayload(MotorGraphIdentity identity) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>(identityPayload(identity));
		payload.put("actuationAuthorized", false);
		payload.put("actuationApplied", false);
		return payload;
	}

	private static String normalizedReason(String reason) {
		return reason == null || reason.isBlank() ? "not_eligible" : reason;
	}

	private static String captureFailureCode(Throwable failure) {
		Throwable current = failure;
		while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
			current = current.getCause();
		}
		return current instanceof MotorFrameCaptureException captureException
			? captureException.code()
			: "capture_failed";
	}
}
