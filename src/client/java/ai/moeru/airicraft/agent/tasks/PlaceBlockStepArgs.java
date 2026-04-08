package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

public record PlaceBlockStepArgs(
	String itemId,
	GoalPosition position
) {
}
