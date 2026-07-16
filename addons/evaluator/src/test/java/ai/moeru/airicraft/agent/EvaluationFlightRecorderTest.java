package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.evaluation.EvaluationBudget;
import ai.moeru.airicraft.agent.evaluation.EvaluationCheckResult;
import ai.moeru.airicraft.agent.evaluation.EvaluationEvidenceSettings;
import ai.moeru.airicraft.agent.evaluation.EvaluationReport;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationStatus;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.motor.MotorFrame;
import ai.moeru.airicraft.agent.motor.MotorFrameCaptureSnapshot;
import ai.moeru.airicraft.agent.motor.MotorFramePreprocessor;
import ai.moeru.airicraft.agent.motor.MotorFrameSource;
import ai.moeru.airicraft.agent.motor.MotorGraphIdentity;
import ai.moeru.airicraft.agent.motor.MotorPolicyClient;
import ai.moeru.airicraft.agent.motor.MotorPolicyEvidence;
import ai.moeru.airicraft.agent.motor.MotorPolicyServiceTiming;
import ai.moeru.airicraft.agent.motor.MotorPolicySession;
import ai.moeru.airicraft.agent.motor.MotorPolicyStepRequest;
import ai.moeru.airicraft.agent.motor.MotorPolicyStepResult;
import ai.moeru.airicraft.agent.motor.MotorSessionCloseRequest;
import ai.moeru.airicraft.agent.motor.MotorSessionCreateRequest;
import ai.moeru.airicraft.agent.motor.MotorShadowObservation;
import ai.moeru.airicraft.agent.motor.MotorShadowRuntime;
import ai.moeru.airicraft.agent.motor.Optimus3MotorShadowCoordinator;
import ai.moeru.airicraft.agent.motor.OptimusCameraAction;
import ai.moeru.airicraft.agent.motor.OptimusPolicyAction;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.evaluator.EvaluationFlightRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationFlightRecorderTest {
	@Test
	void recordsLateCloseAfterRetainedRuntimeShutdown(@TempDir Path tempDir) throws Exception {
		DeferredClosePolicyClient policy = new DeferredClosePolicyClient();
		MotorShadowRuntime shadow = MotorShadowRuntime.enabled(
			policy,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(5),
			7
		);
		Optimus3MotorShadowCoordinator coordinator = new Optimus3MotorShadowCoordinator(
			new IdleFrameSource(),
			shadow,
			Duration.ofMillis(50)
		);
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor(), coordinator);
		EvaluationScenario scenario = scenario();
		EvaluationFlightRecorder recorder = new EvaluationFlightRecorder();
		recorder.start(
			scenario,
			tempDir,
			new EvaluationWorldFixtureService.RestoredWorld("iron-pickaxe", "airicraft_eval_iron-pickaxe", tempDir.resolve("world"))
		);

		shadow.observe(new MotorShadowObservation(identity(), 10L, frame()));
		runtime.motorShadowCleanupComplete();
		recorder.recordTick(
			scenario,
			terminalReport(scenario),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of())
		);
		runtime.finishEvaluation();
		assertFalse(recorder.recordPostFinish(runtime));

		// Simulate a reload clearing the retiring runtime while its close is in flight.
		runtime.shutdown();
		policy.completeClose();
		assertTrue(recorder.recordPostFinish(runtime));

		String events = Files.readString(tempDir.resolve("events.jsonl"), StandardCharsets.UTF_8);
		assertTrue(events.contains("\"type\":\"motor.optimus3_shadow.session_closed\""));
		String cleanup = Files.readString(tempDir.resolve("motor-shadow-cleanup-final.json"), StandardCharsets.UTF_8);
		assertTrue(cleanup.contains("\"cleanupComplete\":true"));
		assertTrue(cleanup.contains("\"requestInFlight\":false"));
		assertTrue(cleanup.contains("\"eventsTruncated\":false"));
	}

	@Test
	void writesSamplesSummaryAndTerminalSnapshots(@TempDir Path tempDir) throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		EvaluationScenario scenario = scenario();
		EvaluationFlightRecorder recorder = new EvaluationFlightRecorder();
		recorder.start(
			scenario,
			tempDir,
			new EvaluationWorldFixtureService.RestoredWorld("iron-pickaxe", "airicraft_eval_iron-pickaxe", tempDir.resolve("world"))
		);

		recorder.recordTick(
			scenario,
			new EvaluationReport(
				EvaluationStatus.RUNNING,
				scenario.id(),
				"running",
				1L,
				1,
				List.of(),
				false,
				Map.of()
			),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of())
		);
		recorder.recordTick(
			scenario,
			terminalReport(scenario),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of("report", "ok"))
		);
		runtime.finishEvaluation();
		assertTrue(recorder.recordPostFinish(runtime));
		int terminalSampleCount = Files.readAllLines(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).size();
		recorder.recordTick(
			scenario,
			new EvaluationReport(
				EvaluationStatus.PASSED,
				scenario.id(),
				"all checks passed",
				3L,
				1,
				List.of(new EvaluationCheckResult("inventory_contains", true, "inventory contains 1x minecraft:iron_pickaxe")),
				false,
				Map.of()
			),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of("report", "ok"))
		);

		String summary = Files.readString(tempDir.resolve("summary.json"), StandardCharsets.UTF_8);
		assertTrue(summary.contains("\"reportStatus\":\"PASSED\""));
		assertTrue(Files.readString(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).contains("\"evaluation_results\""));
		assertEquals(terminalSampleCount, Files.readAllLines(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).size());
		assertTrue(Files.exists(tempDir.resolve("results-final.json")));
		assertTrue(Files.exists(tempDir.resolve("evidence-final.json")));
		assertTrue(Files.readString(tempDir.resolve("agent-status-final.json"), StandardCharsets.UTF_8)
			.contains("\"motorShadow\""));
		assertTrue(Files.readString(tempDir.resolve("motor-shadow-cleanup-final.json"), StandardCharsets.UTF_8)
			.contains("\"cleanupComplete\":true"));
		assertTrue(Files.readString(tempDir.resolve("motor-shadow-cleanup-final.json"), StandardCharsets.UTF_8)
			.contains("\"actuationApplied\":false"));
		assertTrue(Files.exists(tempDir.resolve("agent-debug-llm-calls-final.json")));
	}

	private static EvaluationScenario scenario() {
		return new EvaluationScenario(
			"iron-pickaxe",
			"Iron pickaxe",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"@agent obtain an iron pickaxe",
			EvaluationBudget.defaults(),
			List.of(),
			List.of(),
			EvaluationEvidenceSettings.defaults()
		);
	}

	private static EvaluationReport terminalReport(EvaluationScenario scenario) {
		return new EvaluationReport(
			EvaluationStatus.PASSED,
			scenario.id(),
			"all checks passed",
			2L,
			1,
			List.of(new EvaluationCheckResult("inventory_contains", true, "inventory contains 1x minecraft:iron_pickaxe")),
			false,
			Map.of()
		);
	}

	private static MotorGraphIdentity identity() {
		return new MotorGraphIdentity("execution", "action", "step", "mine_block", 0, "task", "MINE");
	}

	private static MotorFrame frame() {
		return new MotorFrame(
			1L, 1_000L, 1_000_000L, MotorFramePreprocessor.FORMAT,
			128, 128, 854, 480, MotorFramePreprocessor.TRANSFORM,
			"a".repeat(64), "b".repeat(64), new byte[]{1, 2, 3}
		);
	}

	private static final class IdleFrameSource implements MotorFrameSource {
		@Override
		public CompletableFuture<MotorFrame> requestCapture() {
			return new CompletableFuture<>();
		}

		@Override
		public boolean cancelActiveCapture() {
			return false;
		}

		@Override
		public MotorFrameCaptureSnapshot snapshot() {
			return MotorFrameCaptureSnapshot.idle();
		}
	}

	private static final class DeferredClosePolicyClient implements MotorPolicyClient {
		private final CompletableFuture<Void> close = new CompletableFuture<>();

		@Override
		public CompletableFuture<MotorPolicySession> createSession(MotorSessionCreateRequest request) {
			return CompletableFuture.completedFuture(new MotorPolicySession(
				request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt(), 1, Instant.EPOCH
			));
		}

		@Override
		public CompletableFuture<MotorPolicyStepResult> step(MotorPolicyStepRequest request) {
			OptimusPolicyAction action = new OptimusPolicyAction(
				1, 0, new OptimusCameraAction(0, 0), 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
			);
			return CompletableFuture.completedFuture(new MotorPolicyStepResult(
				request.identity(), request.sessionId(), request.generation(), request.stepIndex(),
				request.seed(), request.prompt(), request.minecraftTick(), request.frame().frameId(),
				request.frame().capturedAtMs(), request.frame().encodedSha256(), request.frame().decodedRgbSha256(),
				false, MotorPolicyEvidence.from(action), new MotorPolicyServiceTiming(0, 1, 1),
				1_000_000L, Instant.EPOCH
			));
		}

		@Override
		public CompletableFuture<Void> closeSession(MotorSessionCloseRequest request) {
			return close;
		}

		private void completeClose() {
			assertTrue(close.complete(null));
		}
	}

	private static final class NoopExecutor implements WorldTaskExecutor {
		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}
}
