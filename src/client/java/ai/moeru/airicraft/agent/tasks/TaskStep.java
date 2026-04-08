package ai.moeru.airicraft.agent.tasks;

public enum TaskStep {
	NONE,
	NAVIGATE_TO_TARGET,
	MINE_TARGET,
	WAIT_FOR_PICKUP,
	WAIT,
	ASK_USER,
	CRAFT_RECIPE,
	FINISH
}
