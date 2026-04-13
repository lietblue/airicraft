package ai.moeru.airicraft.agent.llm;

public enum PlannerSessionPhase {
	PLANNER_REQUEST,
	TOOL_WAIT,
	TOOL_FOLLOW_UP,
	COMPLETED,
	FAILED,
	SUPERSEDED
}
