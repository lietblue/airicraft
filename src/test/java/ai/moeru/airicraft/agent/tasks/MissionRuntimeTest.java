package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionRuntimeTest {
	@Test
	void missionLedgerStartsActiveCollectResourceStep() {
		MissionRuntime runtime = new MissionRuntime();
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
					"Collect the requested logs."
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
					"Finish once logs are collected."
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			null,
			"Collect the user's requested logs."
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");
		runtime.tick(
			TaskExecutionSnapshot.idle(),
			new WorldEvidence(
				Map.of(TaskResourceKind.WOOD_LOGS, 0),
				Map.of("minecraft:oak_log", 3),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				101L
			),
			true,
			true,
			101L
		);

		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
		assertEquals("collect_logs", runtime.executionSnapshot().activeStep().id());
		assertEquals(LedgerStepKind.COLLECT_RESOURCE, runtime.executionSnapshot().activeStep().kind());
		assertEquals(GoalType.MINE_BLOCKS, runtime.currentGoal().orElseThrow().type());
	}

	@Test
	void evidenceGatesCollectResourceCompletion() {
		MissionRuntime runtime = new MissionRuntime();
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
					null
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
					null
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			null,
			null
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");
		runtime.tick(
			TaskExecutionSnapshot.idle(),
			new WorldEvidence(
				Map.of(TaskResourceKind.WOOD_LOGS, 0),
				Map.of("minecraft:oak_log", 2),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				101L
			),
			true,
			true,
			101L
		);
		runtime.tick(
			new TaskExecutionSnapshot(TaskExecutionState.RUNNING, runtime.currentGoal().orElseThrow(), "Mine", null, null),
			new WorldEvidence(
				Map.of(TaskResourceKind.WOOD_LOGS, 4),
				Map.of(),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				102L
			),
			true,
			true,
			102L
		);

		assertEquals(TaskState.COMPLETED, runtime.taskSnapshot().state());
		assertEquals("collect_logs", runtime.executionSnapshot().activeStep().id());
		assertEquals(LedgerStepStatus.COMPLETED, runtime.executionSnapshot().ledger().steps().get(0).status());
		assertTrue(runtime.currentGoal().isEmpty());
	}

	@Test
	void plannerMissionUpdateDoesNotCompletePausedCollectTaskFromExistingInventory() {
		MissionRuntime runtime = new MissionRuntime();
		MissionSpec mission = new MissionSpec(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 1 wood logs"
		);
		TaskLedger submittedLedger = new TaskLedger(
			mission.missionId(),
			mission.missionType(),
			mission.goalText(),
			List.of(
				new LedgerStep(
					"collect_resource",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 1, "KEEP"),
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
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 1, null, null)),
					2,
					"Compatibility wrapper mission."
				)
			),
			"collect_resource",
			List.of(),
			"compatibility_submit",
			"Generated from legacy TaskSpec submit."
		);
		WorldEvidence pausedEvidence = new WorldEvidence(
			Map.of(TaskResourceKind.WOOD_LOGS, 21),
			Map.of("minecraft:birch_log", 21),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			101L
		);

		runtime.submit(mission, submittedLedger, 100L, "bridge_debug");
		runtime.tick(
			TaskExecutionSnapshot.idle(),
			pausedEvidence,
			false,
			true,
			101L
		);
		assertEquals(TaskState.PAUSED_BY_SESSION_GATE, runtime.taskSnapshot().state());

		TaskLedger replannedLedger = new TaskLedger(
			mission.missionId(),
			mission.missionType(),
			mission.goalText(),
			submittedLedger.steps(),
			submittedLedger.activeStepId(),
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 1, null, null)),
			"session_gate_paused",
			"Mission paused by session gate."
		);
		runtime.applyPlannerLedger(replannedLedger, 102L, "planner_response");
		runtime.tick(
			TaskExecutionSnapshot.idle(),
			pausedEvidence,
			false,
			true,
			103L
		);

		assertEquals(TaskState.PAUSED_BY_SESSION_GATE, runtime.taskSnapshot().state());
		assertEquals("collect_resource", runtime.taskSnapshot().activeStepId());
		assertEquals(0, runtime.taskSnapshot().progress().collected());
	}

	@Test
	void invalidLedgerCannotReopenCompletedStep() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger completedLedger = new TaskLedger(
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
					LedgerStepStatus.COMPLETED,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					0,
					null
				)
			),
			null,
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			null,
			null
		);
		TaskLedger reopenedLedger = new TaskLedger(
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
					0,
					null
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user asked to continue",
			null
		);

		runtime.applyPlannerLedger(completedLedger, 100L, "planner_response");
		runtime.applyPlannerLedger(reopenedLedger, 101L, "planner_response");

		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertEquals("illegal_ledger_transition", runtime.taskSnapshot().lastFailure());
	}

	@Test
	void cancelledMissionIgnoresSameMissionPlannerUpdate() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 1 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 1, "KEEP"),
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
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 1, null, null)),
					2,
					null
				)
			),
			"collect_logs",
			List.of(),
			null,
			null
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");
		runtime.tick(
			TaskExecutionSnapshot.idle(),
			new WorldEvidence(
				Map.of(TaskResourceKind.WOOD_LOGS, 0),
				Map.of(),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				101L
			),
			false,
			true,
			101L
		);
		runtime.cancel(102L, "user_cancelled");

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());

		runtime.applyPlannerLedger(ledger, 103L, "planner_response");

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
	}

	@Test
	void invalidLedgerCannotActivateStepWhoseDependenciesAreIncomplete() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger invalidLedger = new TaskLedger(
			"mission-craft-1",
			MissionType.CRAFT_ITEM,
			"Wait until logs are collected, then craft",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null, null, null, null, null, null, null, null, null, null, null
					),
					List.of(),
					LedgerStepStatus.PENDING,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					1,
					null
				),
				new LedgerStep(
					"craft_wait",
					LedgerStepKind.WAIT,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null,
						new WaitStepArgs(20L, "wait for crafting window"),
						null,
						null
					),
					List.of("collect_logs"),
					LedgerStepStatus.ACTIVE,
					List.of(),
					0,
					null
				)
			),
			"craft_wait",
			List.of(),
			"invalid_activation",
			null
		);

		runtime.applyPlannerLedger(invalidLedger, 100L, "planner_response");

		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertEquals("illegal_ledger_transition", runtime.taskSnapshot().lastFailure());
	}

	@Test
	void waitStepCompletesAfterRequestedTicks() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger ledger = new TaskLedger(
			"mission-wait-1",
			MissionType.CRAFT_ITEM,
			"Wait briefly",
			List.of(
				new LedgerStep(
					"wait_a_bit",
					LedgerStepKind.WAIT,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null,
						new WaitStepArgs(3L, "wait for settle"),
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(),
					0,
					null
				)
			),
			"wait_a_bit",
			List.of(),
			"user_request",
			null
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");
		runtime.tick(TaskExecutionSnapshot.idle(), new WorldEvidence(Map.of(), Map.of(), Map.of(), "minecraft:overworld", 0, 64, 0, null, 101L), true, false, 101L);
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
		assertEquals(TaskStep.WAIT, runtime.taskSnapshot().currentStep());

		runtime.tick(TaskExecutionSnapshot.idle(), new WorldEvidence(Map.of(), Map.of(), Map.of(), "minecraft:overworld", 0, 64, 0, null, 104L), true, false, 104L);
		assertEquals(TaskState.COMPLETED, runtime.taskSnapshot().state());
		assertEquals(StepExecutionStatus.COMPLETED, runtime.executionSnapshot().lastStepResult().status());
	}

	@Test
	void askUserStepWaitsWithoutFailing() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger ledger = new TaskLedger(
			"mission-ask-1",
			MissionType.DELIVER_ITEM,
			"Ask where to deliver",
			List.of(
				new LedgerStep(
					"ask_destination",
					LedgerStepKind.ASK_USER,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null, null,
						new AskUserStepArgs("Which chest should I use?"),
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(),
					0,
					null
				)
			),
			"ask_destination",
			List.of(),
			"need_clarification",
			null
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");
		runtime.tick(TaskExecutionSnapshot.idle(), new WorldEvidence(Map.of(), Map.of(), Map.of(), "minecraft:overworld", 0, 64, 0, null, 101L), true, false, 101L);

		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
		assertEquals(TaskStep.ASK_USER, runtime.taskSnapshot().currentStep());
		assertEquals(StepExecutionStatus.WAITING, runtime.executionSnapshot().lastStepResult().status());
		assertEquals("Which chest should I use?", runtime.executionSnapshot().lastStepResult().terminalFacts().get("prompt"));
	}

	@Test
	void newMissionIdReplacesCompletedMissionWithoutReusingTerminalStatuses() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger completedLedger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null, null, null, null, null, null, null, null, null, null, null
					),
					List.of(),
					LedgerStepStatus.COMPLETED,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					0,
					null
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null, null, null,
						new FinishStepArgs("done")
					),
					List.of("collect_logs"),
					LedgerStepStatus.COMPLETED,
					List.of(),
					0,
					null
				)
			),
			null,
			List.of(),
			null,
			null
		);
		TaskLedger replacementLedger = new TaskLedger(
			"mission-craft-1",
			MissionType.CRAFT_ITEM,
			"Craft planks",
			List.of(
				new LedgerStep(
					"craft_planks",
					LedgerStepKind.WAIT,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null,
						new WaitStepArgs(5L, "simulate crafting"),
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(),
					0,
					null
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null, null, null,
						new FinishStepArgs("crafted")
					),
					List.of("craft_planks"),
					LedgerStepStatus.PENDING,
					List.of(),
					0,
					null
				)
			),
			"craft_planks",
			List.of(),
			"user_request",
			null
		);

		runtime.applyPlannerLedger(completedLedger, 100L, "planner_response");
		runtime.applyPlannerLedger(replacementLedger, 101L, "planner_response");

		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
		assertEquals("mission-craft-1", runtime.taskSnapshot().mission().missionId());
		assertEquals("craft_planks", runtime.taskSnapshot().activeStepId());
	}

	@Test
	void plannerLedgerWithPendingActiveStepIsNormalizedToActive() {
		MissionRuntime runtime = new MissionRuntime();
		TaskLedger ledger = new TaskLedger(
			"mission-craft-2",
			MissionType.CRAFT_ITEM,
			"Craft planks",
			List.of(
				new LedgerStep(
					"craft_planks",
					LedgerStepKind.WAIT,
					new LedgerStepPayload(
						null, null, null, null, null, null, null, null, null,
						new WaitStepArgs(5L, "simulate crafting"),
						null,
						null
					),
					List.of(),
					LedgerStepStatus.PENDING,
					List.of(),
					0,
					null
				)
			),
			"craft_planks",
			List.of(),
			"user_request",
			null
		);

		runtime.applyPlannerLedger(ledger, 100L, "planner_response");

		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
		assertEquals("craft_planks", runtime.taskSnapshot().activeStepId());
		assertEquals(LedgerStepStatus.ACTIVE, runtime.taskSnapshot().ledger().steps().getFirst().status());
	}
}
