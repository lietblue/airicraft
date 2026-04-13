package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueRuntimeTest {
	@Test
	void mockPlannerResponseProducesDialogueResponse() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Sure, I'll follow you!",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));

		runtime.onPlayerChat("Alice", "@agent follow me", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Sure, I'll follow you!", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertFalse(runtime.isDegraded());
		runtime.shutdown();
	}

	@Test
	void structuredPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Heading there.",
			new PlannerIntent(
				"set_goal",
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(12, 64, -8, true),
				new GoalMineSpec(List.of("minecraft:oak_log"), 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent head there", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Heading there.", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertEquals(GoalType.NAVIGATE_TO, response.intent().goalType());
		assertEquals(new GoalPosition(12, 64, -8, true), response.intent().position());
		assertEquals(new GoalMineSpec(List.of("minecraft:oak_log"), 16), response.intent().mineSpec());
		runtime.shutdown();
	}

	@Test
	void submitTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"On it.",
			new PlannerIntent(
				"submit_task",
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("On it.", response.text());
		assertEquals(DialogueIntentType.SUBMIT_TASK, response.intent().type());
		assertEquals(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void cancelTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Stopping the task.",
			new PlannerIntent(
				"cancel_task",
				null,
				null,
				null,
				null,
				null
			)
		));

		runtime.onPlayerChat("Alice", "@agent stop the task", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Stopping the task.", response.text());
		assertEquals(DialogueIntentType.CANCEL_TASK, response.intent().type());
		assertEquals(null, response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void missionUpdatePlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					2,
					"Collect logs"
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						new FinishStepArgs("Mission complete")
					),
					List.of("collect_logs"),
					LedgerStepStatus.PENDING,
					List.of(new EvidenceRequirement(EvidenceKind.STEP_COMPLETED, null, null, "collect_logs", null)),
					0,
					"Finish"
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);
		backend.injectMockResponse(new PlannerResponse(
			"Starting the mission.",
			new PlannerIntent(
				"mission_update",
				null,
				null,
				null,
				null,
				null,
				ledger
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Starting the mission.", response.text());
		assertEquals(DialogueIntentType.MISSION_UPDATE, response.intent().type());
		assertEquals(ledger, response.intent().taskLedger());
		runtime.shutdown();
	}

	@Test
	void threeTimeoutsEnterDegradedAndResetCommandClearsIt() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = new DialogueRuntime(new PlannerExecutor(backend), 8);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}

		assertTrue(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_entered"));
		assertTrue(runtime.lastResponse().orElseThrow().text().contains("@agent reset"));

		assertTrue(runtime.handleResetCommand("Alice", "@agent reset", 50L, eventBuffer));
		assertFalse(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_cleared"));
		assertTrue(eventBuffer.containsType("planner.reset_requested"));
		assertEquals("Planner state reset.", runtime.lastResponse().orElseThrow().text());
		runtime.shutdown();
	}

	private static DialogueResponse awaitResponse(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = 100L;
		while (Instant.now().isBefore(deadline)) {
			DialogueResponse response = runtime.poll(pollTick++, eventBuffer);
			if (response != null) {
				return response;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for dialogue response");
	}

	private static void awaitFailureProcessed(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, long tick, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = tick + 100L;
		while (Instant.now().isBefore(deadline)) {
			runtime.poll(pollTick++, eventBuffer);
			if (runtime.consecutiveFailureCount() >= tick) {
				return;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for planner failure");
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(10L);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", exception);
		}
	}
}
