package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;

public record PlannerIntent(
	String type,
	GoalType goalType,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec
) {
	public PlannerIntent(String type, GoalType goalType, String targetPlayer) {
		this(type, goalType, targetPlayer, null, null);
	}
}
