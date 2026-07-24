package ai.moeru.airicraft.agent.behavior;

import ai.moeru.airicraft.agent.follow.FollowState;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexAction;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexCause;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorTreeRuntimeTest {
	@Test
	void exposesActiveReflexAndPostReflexSafetyHoldPaths() {
		BehaviorTreeRuntime runtime = new BehaviorTreeRuntime();
		runtime.reflectSurvivalReflex(null, reflex(SurvivalReflexState.ACTIVE));
		assertTrue(runtime.snapshot().activeNodePath().contains("SurvivalReflex"));
		assertTrue(runtime.snapshot().activeNodePath().contains("SWIM_TO_AIR"));

		runtime.reflectSurvivalReflex(null, reflex(SurvivalReflexState.AWAITING_PLANNER));
		assertTrue(runtime.snapshot().activeNodePath().contains("AwaitingPlannerAfterReflex"));
	}

	@Test
	void preservesMovementOwnedByEntityInteractionExecutor() {
		assertTrue(BehaviorTreeRuntime.taskOwnsMovement(new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"task-1",
			null,
			"EntityInteraction",
			"direct_chase",
			null,
			null
		)));

		assertFalse(BehaviorTreeRuntime.taskOwnsMovement(TaskExecutionSnapshot.idle()));
		assertFalse(BehaviorTreeRuntime.taskOwnsMovement(new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"task-2",
			null,
			"ItemDrop",
			"inventory_busy",
			null,
			null
		)));
	}

	@Test
	void leavesCameraToPathingWhileDistantFollowTargetRequiresMovement() {
		GoalSnapshot followGoal = new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alex", 10L, "test");

		assertFalse(BehaviorTreeRuntime.shouldLookAtFollowTarget(
			followGoal,
			new FollowState(true, "Alex", true, true, 12.0D, 10.0D, 64.0D, 10.0D)
		));
		assertTrue(BehaviorTreeRuntime.shouldLookAtFollowTarget(
			followGoal,
			new FollowState(true, "Alex", true, true, 4.0D, 3.0D, 64.0D, 3.0D)
		));
	}

	private static SurvivalReflexSnapshot reflex(SurvivalReflexState state) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 1L, "hold-1",
			"job-1", null, List.of(), 10.0F, 20.0F, 100, 300, 1L, 2L, 0, null
		);
	}
}
