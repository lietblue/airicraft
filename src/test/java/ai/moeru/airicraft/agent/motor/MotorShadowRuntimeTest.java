package ai.moeru.airicraft.agent.motor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorShadowRuntimeTest {
	@Test
	void createsOneSessionPerIdentityStepsAndClosesOnReset() {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = runtime(client);
		MotorGraphIdentity identity = identity("one");

		runtime.observe(observation(identity, 1));
		assertEquals(1, client.createRequests.size());
		MotorSessionCreateRequest create = client.createRequests.remove();
		client.completeCreate(session(create));

		assertEquals(1, client.stepRequests.size());
		MotorPolicyStepRequest first = client.stepRequests.remove();
		client.completeStep(result(first));
		assertEquals(MotorShadowStatus.READY, runtime.snapshot().status());
		assertEquals(1, runtime.snapshot().completedSteps());

		runtime.observe(observation(identity, 2));
		assertEquals(0, client.createRequests.size());
		MotorPolicyStepRequest second = client.stepRequests.remove();
		assertEquals(1, second.stepIndex());
		client.completeStep(result(second));

		runtime.reset();
		assertEquals(1, client.closeRequests.size());
		client.completeClose();
		assertEquals(MotorShadowStatus.IDLE, runtime.snapshot().status());
		assertEquals(1, runtime.snapshot().sessionCreates());
		assertEquals(1, runtime.snapshot().validatedSessions());
		assertEquals(2, runtime.snapshot().completedSteps());
		assertEquals(1, runtime.snapshot().sessionCloses());
		assertEquals(1, client.maxInFlight);
	}

	@Test
	void keepsOnlyLatestObservationBehindOneInFlightStep() {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = runtime(client);
		MotorGraphIdentity identity = identity("one");

		runtime.observe(observation(identity, 1));
		MotorSessionCreateRequest create = client.createRequests.remove();
		client.completeCreate(session(create));
		MotorPolicyStepRequest first = client.stepRequests.remove();

		runtime.observe(observation(identity, 2));
		runtime.observe(observation(identity, 3));
		assertEquals(0, client.stepRequests.size());
		assertEquals(2, runtime.snapshot().backpressuredObservations());
		assertEquals(1, runtime.snapshot().droppedObservations());

		client.completeStep(result(first));
		MotorPolicyStepRequest latest = client.stepRequests.remove();
		assertEquals(3L, latest.frame().frameId());
		assertEquals(1, client.maxInFlight);
	}

	@Test
	void rejectsStaleCreateBeforeOpeningReplacementGeneration() {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = runtime(client);
		MotorGraphIdentity firstIdentity = identity("one");
		MotorGraphIdentity secondIdentity = identity("two");

		runtime.observe(observation(firstIdentity, 1));
		MotorSessionCreateRequest firstCreate = client.createRequests.remove();
		runtime.observe(observation(secondIdentity, 2));
		client.completeCreate(session(firstCreate));

		assertEquals(1, client.closeRequests.size());
		assertEquals(1, runtime.snapshot().staleResults());
		assertNull(runtime.snapshot().sessionId(), "a stale session must never be exposed under the replacement identity");
		assertNull(runtime.snapshot().lastResult());
		client.completeClose();

		MotorSessionCreateRequest secondCreate = client.createRequests.remove();
		assertEquals(secondIdentity, secondCreate.identity());
		assertEquals(2, secondCreate.generation());
		client.completeCreate(session(secondCreate));
		assertEquals(secondIdentity, client.stepRequests.element().identity());
		assertEquals(1, client.maxInFlight);
	}

	@Test
	void timeoutQuarantinesGenerationWithoutRetry() throws Exception {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(client, Duration.ofMillis(30), 7L, new SessionIds());
		MotorGraphIdentity identity = identity("one");

		runtime.observe(observation(identity, 1));
		await(() -> runtime.snapshot().sessionCloses() == 1);
		assertNull(runtime.snapshot().sessionId(), "an ambiguous create is cleanup state, not a validated session");
		assertEquals("session-1", client.closeRequests.element().session().sessionId());
		client.completeClose();
		await(() -> runtime.snapshot().status() == MotorShadowStatus.FAILED);
		assertEquals(1, runtime.snapshot().timeouts());
		assertEquals(1, runtime.snapshot().sessionCreates());
		assertEquals(0, runtime.snapshot().validatedSessions());
		assertEquals(1, runtime.snapshot().sessionCloses());
		assertEquals("timeout", runtime.snapshot().lastFailureCode());

		runtime.observe(observation(identity, 2));
		assertEquals(1, runtime.snapshot().sessionCreates());
		assertEquals(1, runtime.snapshot().suppressedAfterFailure());

		runtime.observe(observation(identity("two"), 3));
		assertEquals(2, runtime.snapshot().sessionCreates());
		assertEquals(MotorShadowStatus.CREATING_SESSION, runtime.snapshot().status());
	}

	@Test
	void createMayOutliveStepBudgetWhileStepStillUsesShortDeadline() throws Exception {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			Duration.ofMillis(30),
			7L,
			new SessionIds()
		);

		runtime.observe(observation(identity("one"), 1));
		MotorSessionCreateRequest create = client.createRequests.remove();
		Thread.sleep(80L);
		assertEquals(MotorShadowStatus.CREATING_SESSION, runtime.snapshot().status());
		assertEquals(0, runtime.snapshot().timeouts());

		client.completeCreate(session(create));
		await(() -> runtime.snapshot().sessionCloses() == 1);
		assertEquals(1, runtime.snapshot().timeouts());
		assertEquals("timeout", runtime.snapshot().lastFailureCode());
		client.completeClose();
		await(() -> runtime.snapshot().status() == MotorShadowStatus.FAILED);
		assertEquals(1, runtime.snapshot().sessionCreates());
		assertEquals(1, runtime.snapshot().stepRequests());
	}

	@Test
	void closeUsesASeparateBoundedDeadline() throws Exception {
		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = MotorShadowRuntime.enabled(
			client,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofMillis(30),
			7L,
			new SessionIds()
		);

		runtime.observe(observation(identity("one"), 1));
		MotorSessionCreateRequest create = client.createRequests.remove();
		client.completeCreate(session(create));
		MotorPolicyStepRequest step = client.stepRequests.remove();
		client.completeStep(result(step));
		runtime.reset();

		await(() -> runtime.snapshot().status() == MotorShadowStatus.IDLE);
		assertEquals(1, runtime.snapshot().timeouts());
		assertEquals("close_timeout", runtime.snapshot().lastFailureCode());
		assertEquals(1, runtime.snapshot().sessionCloses());
	}

	@Test
	void rejectsSeedsOutsideUnsignedThirtyTwoBitRange() {
		FakeClient client = new FakeClient();
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
			MotorShadowRuntime.enabled(client, Duration.ofSeconds(1), -1L)
		);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
			MotorShadowRuntime.enabled(client, Duration.ofSeconds(1), 0x1_0000_0000L)
		);
	}

	@Test
	void disabledRuntimeIsANoopAndSnapshotNeverContainsImagePayload() {
		MotorShadowRuntime disabled = MotorShadowRuntime.disabled();
		disabled.observe(observation(identity("one"), 1));
		assertEquals(MotorShadowStatus.DISABLED, disabled.snapshot().status());

		FakeClient client = new FakeClient();
		MotorShadowRuntime runtime = runtime(client);
		runtime.observe(observation(identity("one"), 1));
		String snapshot = runtime.snapshot().toString();
		assertFalse(snapshot.contains("AQID"));
		assertFalse(snapshot.contains("pngBytes"));
	}

	private static MotorShadowRuntime runtime(FakeClient client) {
		return MotorShadowRuntime.enabled(client, Duration.ofSeconds(1), 7L, new SessionIds());
	}

	private static MotorGraphIdentity identity(String suffix) {
		return new MotorGraphIdentity(
			"execution-" + suffix,
			"action-" + suffix,
			"step-" + suffix,
			"mine_block",
			0,
			"task-" + suffix,
			"BREAK_BLOCKS"
		);
	}

	private static MotorShadowObservation observation(MotorGraphIdentity identity, long frameId) {
		return new MotorShadowObservation(identity, 100L + frameId, new MotorFrame(
			frameId,
			1_000L + frameId,
			10_000L + frameId,
			MotorFramePreprocessor.FORMAT,
			128,
			128,
			854,
			480,
			MotorFramePreprocessor.TRANSFORM,
			"a".repeat(64),
			"b".repeat(64),
			new byte[]{1, 2, 3}
		));
	}

	private static MotorPolicySession session(MotorSessionCreateRequest request) {
		return new MotorPolicySession(
			request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt(), 1, Instant.EPOCH
		);
	}

	private static MotorPolicyStepResult result(MotorPolicyStepRequest request) {
		OptimusPolicyAction action = noopAction();
		return new MotorPolicyStepResult(
			request.identity(), request.sessionId(), request.generation(), request.stepIndex(), request.seed(), request.prompt(),
			request.minecraftTick(), request.frame().frameId(), request.frame().capturedAtMs(),
			request.frame().encodedSha256(), request.frame().decodedRgbSha256(), false,
			MotorPolicyEvidence.from(action), new MotorPolicyServiceTiming(0, 1, 1), 1_000_000L, Instant.EPOCH
		);
	}

	static OptimusPolicyAction noopAction() {
		return new OptimusPolicyAction(
			0, 0, new OptimusCameraAction(0, 0), 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
		);
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(5L);
		}
		assertTrue(condition.getAsBoolean(), "condition did not become true before deadline");
	}

	private static final class SessionIds implements java.util.function.Supplier<String> {
		private long next;

		@Override
		public String get() {
			return "session-" + (++next);
		}
	}

	private static final class FakeClient implements MotorPolicyClient {
		private final Queue<MotorSessionCreateRequest> createRequests = new ArrayDeque<>();
		private final Queue<MotorPolicyStepRequest> stepRequests = new ArrayDeque<>();
		private final Queue<MotorSessionCloseRequest> closeRequests = new ArrayDeque<>();
		private CompletableFuture<MotorPolicySession> createFuture;
		private CompletableFuture<MotorPolicyStepResult> stepFuture;
		private CompletableFuture<Void> closeFuture;
		private final java.util.List<CompletableFuture<?>> allFutures = new java.util.ArrayList<>();
		private int maxInFlight;

		@Override
		public CompletableFuture<MotorPolicySession> createSession(MotorSessionCreateRequest request) {
			createRequests.add(request);
			createFuture = track(new CompletableFuture<>());
			return createFuture;
		}

		@Override
		public CompletableFuture<MotorPolicyStepResult> step(MotorPolicyStepRequest request) {
			stepRequests.add(request);
			stepFuture = track(new CompletableFuture<>());
			return stepFuture;
		}

		@Override
		public CompletableFuture<Void> closeSession(MotorSessionCloseRequest request) {
			closeRequests.add(request);
			closeFuture = track(new CompletableFuture<>());
			return closeFuture;
		}

		private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
			int concurrent = 1;
			for (CompletableFuture<?> existing : allFutures) {
				if (!existing.isDone()) {
					concurrent++;
				}
			}
			allFutures.add(future);
			maxInFlight = Math.max(maxInFlight, concurrent);
			return future;
		}

		private void completeCreate(MotorPolicySession session) {
			createFuture.complete(session);
		}

		private void completeStep(MotorPolicyStepResult result) {
			stepFuture.complete(result);
		}

		private void completeClose() {
			closeFuture.complete(null);
		}
	}
}
