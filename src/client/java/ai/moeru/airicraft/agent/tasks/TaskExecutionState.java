package ai.moeru.airicraft.agent.tasks;

public enum TaskExecutionState {
	IDLE,
	PAUSED_BY_SESSION_GATE,
	PAUSED_BY_REFLEX,
	RUNNING,
	COMPLETED,
	FAILED,
	CANCELLED
}
