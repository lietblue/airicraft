package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.TaskSpec;

public record DialogueIntent(
	DialogueIntentType type,
	GoalType goalType,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
	TaskSpec taskSpec
) {
	public DialogueIntent(DialogueIntentType type, GoalType goalType, String targetPlayer) {
		this(type, goalType, targetPlayer, null, null, null);
	}

	public DialogueIntent(DialogueIntentType type, GoalType goalType, String targetPlayer, GoalPosition position, GoalMineSpec mineSpec) {
		this(type, goalType, targetPlayer, position, mineSpec, null);
	}

	public DialogueIntent(DialogueIntentType type, GoalType goalType, String targetPlayer, GoalPosition position, GoalMineSpec mineSpec, TaskSpec taskSpec) {
		this.type = type;
		this.goalType = goalType;
		this.targetPlayer = targetPlayer;
		this.position = position;
		this.mineSpec = mineSpec;
		this.taskSpec = taskSpec;
	}
}
