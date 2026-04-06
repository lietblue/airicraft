package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;

public record DialogueIntent(
	DialogueIntentType type,
	GoalType goalType,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec
) {
	public DialogueIntent(DialogueIntentType type, GoalType goalType, String targetPlayer) {
		this(type, goalType, targetPlayer, null, null);
	}
}
