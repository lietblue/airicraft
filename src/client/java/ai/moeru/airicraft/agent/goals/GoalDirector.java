package ai.moeru.airicraft.agent.goals;

import ai.moeru.airicraft.agent.dialogue.DialogueResponse;

import java.util.Optional;

public final class GoalDirector {
	private GoalSnapshot activeGoal;

	public void onPlannerResponse(DialogueResponse response) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}

		switch (response.intent().type()) {
			case SET_GOAL -> applySetGoal(response);
			case CLEAR_GOAL -> activeGoal = null;
			default -> {
			}
		}
	}

	public Optional<GoalSnapshot> activeGoal() {
		return Optional.ofNullable(activeGoal);
	}

	public void clearFollowGoal(String targetPlayer) {
		if (activeGoal == null || activeGoal.type() != GoalType.FOLLOW_PLAYER) {
			return;
		}
		if (targetPlayer == null || !targetPlayer.equals(activeGoal.targetPlayer())) {
			return;
		}
		activeGoal = null;
	}

	public void clear() {
		activeGoal = null;
	}

	private void applySetGoal(DialogueResponse response) {
		if (response.intent().goalType() == null) {
			return;
		}
		if (response.intent().goalType() == GoalType.FOLLOW_PLAYER
			&& (response.intent().targetPlayer() == null || response.intent().targetPlayer().isBlank())) {
			return;
		}
		if (response.intent().goalType() == GoalType.NAVIGATE_TO && response.intent().position() == null) {
			return;
		}
		if (response.intent().goalType() == GoalType.MINE_BLOCKS && response.intent().mineSpec() == null) {
			return;
		}
		activeGoal = new GoalSnapshot(
			response.intent().goalType(),
			response.intent().targetPlayer(),
			response.intent().position(),
			response.intent().mineSpec(),
			response.tick(),
			"planner_response"
		);
	}
}
