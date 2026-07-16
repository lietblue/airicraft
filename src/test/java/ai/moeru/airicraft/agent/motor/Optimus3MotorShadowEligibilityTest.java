package ai.moeru.airicraft.agent.motor;

import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionState;
import ai.moeru.airicraft.agent.actions.ActionPlanStep;
import ai.moeru.airicraft.agent.actions.ActionRoute;
import ai.moeru.airicraft.agent.actions.ActionStepKind;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Optimus3MotorShadowEligibilityTest {
	@Test
	void acceptsOnlyTheExactGraphOwnedRawIronMinePrimitive() {
		MotorShadowEligibilityDecision decision = Optimus3MotorShadowEligibility.evaluate(
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);

		assertTrue(decision.eligible());
		assertEquals("eligible", decision.reason());
		assertEquals(new MotorGraphIdentity(
			"execution-1",
			"resource-gathering",
			"mine-raw-iron",
			"mine_block",
			2,
			"task-1",
			"MINE"
		), decision.identity());
	}

	@Test
	void rejectsNearMissesBeforeExposingAnyMotorIdentity() {
		assertIneligible(
			"session_not_eligible",
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 10),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		assertIneligible(
			"not_action_graph_owned",
			eligibleSession(),
			taskOwnedBy("planner_response"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		MotorShadowEligibilityDecision thirdPerson = Optimus3MotorShadowEligibility.evaluate(
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1")),
			false
		);
		assertFalse(thirdPerson.eligible());
		assertEquals("perspective_not_first_person", thirdPerson.reason());
		assertNull(thirdPerson.identity());
		assertIneligible(
			"graph_not_waiting_primitive",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.OBSERVING, rawIronStep(), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		assertIneligible(
			"not_raw_iron_mine_block",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, mineStep(
				Map.of("itemId", "minecraft:iron_ingot", "blockIds", List.of("minecraft:iron_ore"))
			), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		assertIneligible(
			"not_raw_iron_mine_block",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, mineStep(
				Map.of("itemId", "minecraft:raw_iron", "blockIds", List.of("minecraft:dirt"))
			), "task-1", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		assertIneligible(
			"world_task_not_mine",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", "execution-1"),
			Optional.of(new WorldTaskRequest(
				"task-1",
				"job-1",
				WorldTaskType.FOLLOW,
				new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alex", 10, "action_graph")
			))
		);
		assertIneligible(
			"graph_task_mismatch",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "different-task", "execution-1"),
			Optional.of(mineRequest("task-1"))
		);
		assertIneligible(
			"graph_provenance_incomplete",
			eligibleSession(),
			taskOwnedBy("action_graph"),
			graph(ActionGraphExecutionState.WAITING_PRIMITIVE, rawIronStep(), "task-1", ""),
			Optional.of(mineRequest("task-1"))
		);
	}

	private static void assertIneligible(
		String reason,
		SessionSnapshot session,
		TaskSnapshot task,
		ActionGraphExecutionSnapshot graph,
		Optional<WorldTaskRequest> activeTask
	) {
		MotorShadowEligibilityDecision decision = Optimus3MotorShadowEligibility.evaluate(
			session,
			task,
			graph,
			activeTask
		);
		assertFalse(decision.eligible());
		assertEquals(reason, decision.reason());
		assertNull(decision.identity());
	}

	private static SessionSnapshot eligibleSession() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			10
		);
	}

	private static TaskSnapshot taskOwnedBy(String source) {
		TaskSnapshot idle = TaskSnapshot.idle();
		return new TaskSnapshot(
			idle.state(),
			idle.mission(),
			idle.ledger(),
			idle.spec(),
			idle.progress(),
			idle.currentStep(),
			idle.currentGoalOwnership(),
			source,
			idle.lastFailure(),
			idle.activeStepId(),
			idle.activeStepKind(),
			idle.lastStepResult(),
			idle.updatedTick()
		);
	}

	private static ActionGraphExecutionSnapshot graph(
		ActionGraphExecutionState state,
		ActionPlanStep step,
		String activeTaskId,
		String executionId
	) {
		return new ActionGraphExecutionSnapshot(
			true,
			executionId,
			state,
			null,
			ActionRoute.empty(),
			0,
			step,
			2,
			0,
			0,
			"",
			activeTaskId,
			"",
			"",
			Map.of(),
			List.of(),
			List.of(),
			Map.of(),
			Map.of(),
			Map.of()
		);
	}

	private static ActionPlanStep rawIronStep() {
		return mineStep(Map.of(
			"itemId", "minecraft:raw_iron",
			"blockIds", List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore")
		));
	}

	private static ActionPlanStep mineStep(Map<String, Object> args) {
		return new ActionPlanStep(
			ActionStepKind.PRIMITIVE,
			"resource-gathering",
			"direct-mine",
			"mine-raw-iron",
			"mine_block",
			args
		);
	}

	private static WorldTaskRequest mineRequest(String taskId) {
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:iron_ore"), 1),
			10,
			"action_graph"
		);
		return WorldTaskRequest.collectMine(taskId, "job-1", goal);
	}
}
