package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionMode;

public record PlannerAmbientContext(
	SessionMode sessionMode,
	String primaryInteractionPlayer,
	String activeGoalDescription
) {
	public static PlannerAmbientContext fromRequest(PlannerRequest request) {
		return new PlannerAmbientContext(
			request.sessionMode(),
			normalize(request.primaryInteractionPlayer()),
			describeGoal(request.activeGoal())
		);
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String describeGoal(GoalSnapshot goal) {
		if (goal == null || goal.type() == null) {
			return null;
		}
		return switch (goal.type()) {
			case FOLLOW_PLAYER -> goal.targetPlayer() == null || goal.targetPlayer().isBlank()
				? "Follow the current player."
				: "Follow " + goal.targetPlayer() + ".";
			case NAVIGATE_TO -> goal.position() == null
				? "Navigate to the requested position."
				: "Navigate to "
					+ goal.position().x() + ", "
					+ goal.position().y() + ", "
					+ goal.position().z()
					+ (goal.position().exactY() ? " with exact Y." : ".");
			case MINE_BLOCKS -> goal.mineSpec() == null
				? "Mine the requested blocks."
				: "Mine "
					+ goal.mineSpec().quantity()
					+ " of "
					+ String.join(", ", goal.mineSpec().blockIds())
					+ ".";
		};
	}
}
