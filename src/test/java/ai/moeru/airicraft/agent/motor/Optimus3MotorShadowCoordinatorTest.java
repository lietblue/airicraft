package ai.moeru.airicraft.agent.motor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Optimus3MotorShadowCoordinatorTest {
	@Test
	void recordsEvidenceOnlyLifecycleAndBackpressuresCaptureBehindPolicyStep() {
		FakeFrameSource frames = new FakeFrameSource();
		FakePolicyClient client = new FakePolicyClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			Duration.ofSeconds(5),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(MotorPolicyContract.CONTROL_DEADLINE_MILLIS)
		);
		MotorShadowEligibilityDecision eligible = MotorShadowEligibilityDecision.eligible(identity("one"));

		coordinator.tick(eligible, 100);
		coordinator.tick(eligible, 101);
		assertEquals(1, frames.requests.size(), "one capture may be pending at a time");

		frames.complete(0, frame(1));
		assertEquals(1, client.createRequests.size());
		assertEquals(1, client.stepRequests.size());

		coordinator.tick(eligible, 102);
		coordinator.tick(eligible, 103);
		assertEquals(1, frames.requests.size(), "capture must wait behind the in-flight policy step");

		MotorPolicyStepRequest firstStep = client.stepRequests.getFirst();
		client.completeStep(result(firstStep));
		coordinator.tick(eligible, 104);
		assertEquals(2, frames.requests.size(), "a fresh capture may start after evidence completes");

		Map<String, Object> status = coordinator.snapshotPayload();
		assertEquals(false, status.get("actuationAuthorized"));
		assertEquals(false, status.get("actuationApplied"));
		assertEquals(true, status.get("eligible"));
		assertEquals(1L, status.get("completedSteps"));
		assertFalse(status.toString().contains("pngBytes"));

		List<MotorShadowEvent> evidence = coordinator.drainEvents();
		MotorShadowEvent started = event(evidence, "motor.optimus3_shadow.session_started");
		assertEquals("session-test", started.payload().get("sessionId"));
		assertEquals(1, started.payload().get("recurrentResetCount"));
		assertEquals(false, started.payload().get("actuationApplied"));
		MotorShadowEvent step = event(evidence, "motor.optimus3_shadow.step");
		assertEquals(false, step.payload().get("actuationAuthorized"));
		assertEquals(false, step.payload().get("actuationApplied"));
		assertEquals(1L, step.payload().get("frameId"));
		assertEquals(50L, step.payload().get("deadlineMillis"));
		assertEquals(false, step.payload().get("deadlineMiss"));
		assertNotNull(step.payload().get("rawAction"));
		assertNotNull(step.payload().get("safeShadowAction"));
		assertFalse(step.payload().toString().contains("pngBytes"));

		coordinator.reset("graph_finished");
		assertEquals(1, client.closeRequests.size());
		Map<String, Object> resetStatus = coordinator.snapshotPayload();
		assertEquals(false, resetStatus.get("actuationAuthorized"));
		assertEquals(false, resetStatus.get("actuationApplied"));
		assertEquals(false, resetStatus.get("eligible"));
		MotorShadowEvent stopped = event(coordinator.drainEvents(), "motor.optimus3_shadow.session_stopped");
		assertEquals("graph_finished", stopped.payload().get("reason"));
		assertEquals(false, stopped.payload().get("actuationAuthorized"));
		assertEquals(false, stopped.payload().get("actuationApplied"));
		coordinator.close();
	}

	@Test
	void captureFailureQuarantinesTheIdentityWithoutRetrying() {
		FakeFrameSource frames = new FakeFrameSource();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			new FakePolicyClient(),
			Duration.ofSeconds(1),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(MotorPolicyContract.CONTROL_DEADLINE_MILLIS)
		);
		MotorShadowEligibilityDecision eligible = MotorShadowEligibilityDecision.eligible(identity("one"));

		coordinator.tick(eligible, 100);
		frames.fail(0, new MotorFrameCaptureException("perspective_not_first_person", "third person"));
		coordinator.tick(eligible, 101);
		coordinator.tick(eligible, 102);

		assertEquals(1, frames.requests.size(), "a failed capture generation must not be retried");
		Map<String, Object> status = coordinator.snapshotPayload();
		assertEquals("FAILED", status.get("status"));
		assertEquals(1L, status.get("captureFailures"));
		assertEquals("perspective_not_first_person", status.get("lastFailureCode"));
		MotorShadowEvent failed = event(coordinator.drainEvents(), "motor.optimus3_shadow.failed");
		assertEquals(false, failed.payload().get("actuationAuthorized"));
		assertEquals(false, failed.payload().get("actuationApplied"));
		coordinator.close();
	}

	@Test
	void cancelsAStaleCaptureWhenGraphIdentityChanges() {
		FakeFrameSource frames = new FakeFrameSource();
		FakePolicyClient client = new FakePolicyClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(250)
		);

		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("one")), 100);
		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("two")), 101);

		assertEquals(2, frames.requests.size());
		assertEquals(1, frames.cancelCalls);
		assertTrue(frames.requests.getFirst().isCancelled());
		assertFalse(frames.requests.getFirst().complete(frame(1)));
		assertTrue(client.createRequests.isEmpty(), "the stale frame must never reach the policy client");

		frames.complete(1, frame(2));
		assertEquals(identity("two"), client.createRequests.getFirst().identity());
		assertEquals(identity("two"), client.stepRequests.getFirst().identity());
		client.completeStep(result(client.stepRequests.getFirst()));
		coordinator.reset("test_cleanup");
		coordinator.close();
	}

	@Test
	void identityChangeImmediatelyRetiresAValidatedSession() {
		FakeFrameSource frames = new FakeFrameSource();
		FakePolicyClient client = new FakePolicyClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(MotorPolicyContract.CONTROL_DEADLINE_MILLIS)
		);

		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("one")), 100);
		frames.complete(0, frame(1));
		client.completeStep(result(client.stepRequests.getFirst()));
		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("one")), 101);
		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("two")), 102);

		assertEquals(1, client.closeRequests.size());
		assertEquals("", coordinator.snapshotPayload().get("sessionId"));
		List<MotorShadowEvent> events = coordinator.drainEvents();
		MotorShadowEvent stopped = event(events, "motor.optimus3_shadow.session_stopped");
		assertEquals("identity_changed", stopped.payload().get("reason"));
		assertEquals(false, stopped.payload().get("actuationApplied"));
		assertEquals(identity("two"), coordinator.snapshotPayload().get("identity") instanceof Map<?, ?>
			? identityFromPayload((Map<?, ?>) coordinator.snapshotPayload().get("identity"))
			: null);
		coordinator.close();
	}

	@Test
	void fastFirstStepFailureStillRecordsValidatedStartFailureAndStop() {
		FakeFrameSource frames = new FakeFrameSource();
		FakePolicyClient client = new FakePolicyClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(MotorPolicyContract.CONTROL_DEADLINE_MILLIS)
		);
		MotorShadowEligibilityDecision eligible = MotorShadowEligibilityDecision.eligible(identity("one"));

		coordinator.tick(eligible, 100);
		frames.complete(0, frame(1));
		client.failStep(new MotorPolicyException("invalid_response_schema", "bad action"));
		coordinator.tick(eligible, 101);

		List<MotorShadowEvent> events = coordinator.drainEvents();
		assertEquals(1, events.stream().filter(value -> value.type().endsWith("session_started")).count());
		assertEquals(1, events.stream().filter(value -> value.type().endsWith("failed")).count());
		assertEquals(1, events.stream().filter(value -> value.type().endsWith("session_stopped")).count());
		assertEquals(1, events.stream().filter(value -> value.type().endsWith("session_closed")).count());
		coordinator.close();
	}

	@Test
	void cleanupWaitsForCloseAndRetainsItsFailureEvidence() {
		FakeFrameSource frames = new FakeFrameSource();
		FakePolicyClient client = new FakePolicyClient();
		client.deferClose = true;
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(5),
			7,
			() -> "session-test"
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			frames,
			runtime,
			Duration.ofMillis(MotorPolicyContract.CONTROL_DEADLINE_MILLIS)
		);

		coordinator.tick(MotorShadowEligibilityDecision.eligible(identity("one")), 100);
		frames.complete(0, frame(1));
		client.completeStep(result(client.stepRequests.getFirst()));
		coordinator.reset("evaluation_finished");

		assertFalse(coordinator.cleanupComplete());
		client.failClose(new MotorPolicyException("timeout", "close timed out"));
		assertTrue(coordinator.cleanupComplete());
		assertEquals("close_timeout", coordinator.snapshotPayload().get("lastFailureCode"));
		List<MotorShadowEvent> events = coordinator.drainEvents();
		MotorShadowEvent failed = event(events, "motor.optimus3_shadow.failed");
		assertEquals("close_timeout", failed.payload().get("failureCode"));
		MotorShadowEvent closed = event(events, "motor.optimus3_shadow.session_closed");
		assertEquals(false, closed.payload().get("closeSucceeded"));
		coordinator.close();
	}

	private static MotorGraphIdentity identityFromPayload(Map<?, ?> payload) {
		return new MotorGraphIdentity(
			(String) payload.get("executionId"),
			(String) payload.get("graphActionId"),
			(String) payload.get("graphStepId"),
			(String) payload.get("graphPrimitive"),
			((Number) payload.get("stepAttempt")).intValue(),
			(String) payload.get("taskId"),
			(String) payload.get("taskType")
		);
	}

	private static MotorShadowEvent event(List<MotorShadowEvent> events, String type) {
		return events.stream()
			.filter(event -> type.equals(event.type()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing event " + type + " in " + events));
	}

	private static MotorGraphIdentity identity(String suffix) {
		return new MotorGraphIdentity(
			"execution-" + suffix,
			"action-" + suffix,
			"step-" + suffix,
			"mine_block",
			0,
			"task-" + suffix,
			"MINE"
		);
	}

	private static MotorFrame frame(long frameId) {
		return new MotorFrame(
			frameId,
			1_000 + frameId,
			10_000 + frameId,
			MotorFramePreprocessor.FORMAT,
			128,
			128,
			854,
			480,
			MotorFramePreprocessor.TRANSFORM,
			"a".repeat(64),
			"b".repeat(64),
			new byte[]{1, 2, 3}
		);
	}

	private static MotorPolicyStepResult result(MotorPolicyStepRequest request) {
		OptimusPolicyAction rawAction = new OptimusPolicyAction(
			1, 0, new OptimusCameraAction(0, 0), 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
		);
		return new MotorPolicyStepResult(
			request.identity(),
			request.sessionId(),
			request.generation(),
			request.stepIndex(),
			request.seed(),
			request.prompt(),
			request.minecraftTick(),
			request.frame().frameId(),
			request.frame().capturedAtMs(),
			request.frame().encodedSha256(),
			request.frame().decodedRgbSha256(),
			false,
			MotorPolicyEvidence.from(rawAction),
			new MotorPolicyServiceTiming(2, 3, 5),
			25_000_000L,
			Instant.ofEpochMilli(request.frame().capturedAtMs() + 25)
		);
	}

	private static final class FakeFrameSource implements MotorFrameSource {
		private final List<CompletableFuture<MotorFrame>> requests = new ArrayList<>();
		private CompletableFuture<MotorFrame> active;
		private int cancelCalls;

		@Override
		public CompletableFuture<MotorFrame> requestCapture() {
			active = new CompletableFuture<>();
			requests.add(active);
			return active;
		}

		@Override
		public boolean cancelActiveCapture() {
			if (active == null || active.isDone()) {
				return false;
			}
			cancelCalls++;
			return active.cancel(false);
		}

		@Override
		public MotorFrameCaptureSnapshot snapshot() {
			return new MotorFrameCaptureSnapshot(
				active != null && !active.isDone(),
				0,
				active != null && !active.isDone() ? "pending" : "idle"
			);
		}

		private void complete(int requestIndex, MotorFrame frame) {
			assertTrue(requests.get(requestIndex).complete(frame));
		}

		private void fail(int requestIndex, Throwable failure) {
			assertTrue(requests.get(requestIndex).completeExceptionally(failure));
		}
	}

	private static final class FakePolicyClient implements MotorPolicyClient {
		private final List<MotorSessionCreateRequest> createRequests = new ArrayList<>();
		private final List<MotorPolicyStepRequest> stepRequests = new ArrayList<>();
		private final List<MotorSessionCloseRequest> closeRequests = new ArrayList<>();
		private CompletableFuture<MotorPolicyStepResult> stepFuture;
		private CompletableFuture<Void> closeFuture;
		private boolean deferClose;

		@Override
		public CompletableFuture<MotorPolicySession> createSession(MotorSessionCreateRequest request) {
			createRequests.add(request);
			return CompletableFuture.completedFuture(new MotorPolicySession(
				request.identity(),
				request.sessionId(),
				request.generation(),
				request.seed(),
				request.prompt(),
				1,
				Instant.EPOCH
			));
		}

		@Override
		public CompletableFuture<MotorPolicyStepResult> step(MotorPolicyStepRequest request) {
			stepRequests.add(request);
			stepFuture = new CompletableFuture<>();
			return stepFuture;
		}

		@Override
		public CompletableFuture<Void> closeSession(MotorSessionCloseRequest request) {
			closeRequests.add(request);
			closeFuture = new CompletableFuture<>();
			if (!deferClose) {
				closeFuture.complete(null);
			}
			return closeFuture;
		}

		private void completeStep(MotorPolicyStepResult result) {
			assertTrue(stepFuture.complete(result));
		}

		private void failStep(Throwable failure) {
			assertTrue(stepFuture.completeExceptionally(failure));
		}

		private void failClose(Throwable failure) {
			assertTrue(closeFuture.completeExceptionally(failure));
		}
	}
}
