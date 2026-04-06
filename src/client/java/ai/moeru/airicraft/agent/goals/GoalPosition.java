package ai.moeru.airicraft.agent.goals;

public record GoalPosition(
	int x,
	int y,
	int z,
	boolean exactY
) {
}
