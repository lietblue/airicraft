package ai.moeru.airicraft.agent.goals;

import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalDirectorTest {
	@Test
	void plannerSetGoalCreatesFollowGoal() {
		GoalDirector goalDirector = new GoalDirector();

		goalDirector.onPlannerResponse(new DialogueResponse(
			"Following Alice.",
			new DialogueIntent(DialogueIntentType.SET_GOAL, GoalType.FOLLOW_PLAYER, "Alice"),
			42L
		));

		GoalSnapshot goal = goalDirector.activeGoal().orElseThrow();
		assertEquals(GoalType.FOLLOW_PLAYER, goal.type());
		assertEquals("Alice", goal.targetPlayer());
	}

	@Test
	void plannerSetGoalCreatesNavigateGoal() {
		GoalDirector goalDirector = new GoalDirector();

		goalDirector.onPlannerResponse(new DialogueResponse(
			"Heading there.",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(12, 64, -8, true),
				null
			),
			42L
		));

		GoalSnapshot goal = goalDirector.activeGoal().orElseThrow();
		assertEquals(GoalType.NAVIGATE_TO, goal.type());
		assertEquals(new GoalPosition(12, 64, -8, true), goal.position());
	}

	@Test
	void plannerSetGoalCreatesMineBlocksGoal() {
		GoalDirector goalDirector = new GoalDirector();

		goalDirector.onPlannerResponse(new DialogueResponse(
			"Mining logs.",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				GoalType.MINE_BLOCKS,
				null,
				null,
				new GoalMineSpec(List.of("minecraft:oak_log"), 16)
			),
			42L
		));

		GoalSnapshot goal = goalDirector.activeGoal().orElseThrow();
		assertEquals(GoalType.MINE_BLOCKS, goal.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:oak_log"), 16), goal.mineSpec());
	}

	@Test
	void nonGoalIntentDoesNotCreateGoal() {
		GoalDirector goalDirector = new GoalDirector();

		goalDirector.onPlannerResponse(new DialogueResponse(
			"Hello there.",
			new DialogueIntent(DialogueIntentType.REPLY_ONLY, null, null),
			42L
		));

		assertTrue(goalDirector.activeGoal().isEmpty());
	}

	@Test
	void clearIntentRemovesActiveGoal() {
		GoalDirector goalDirector = new GoalDirector();
		goalDirector.onPlannerResponse(new DialogueResponse(
			"Following Alice.",
			new DialogueIntent(DialogueIntentType.SET_GOAL, GoalType.FOLLOW_PLAYER, "Alice"),
			42L
		));

		goalDirector.onPlannerResponse(new DialogueResponse(
			"Stopping.",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			50L
		));

		assertTrue(goalDirector.activeGoal().isEmpty());
	}
}
