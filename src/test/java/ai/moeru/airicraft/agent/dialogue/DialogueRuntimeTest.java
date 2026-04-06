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
