package ai.moeru.airicraft.agent.tasks;

public enum TaskExecutionState {
	IDLE,
	PAUSED_BY_SESSION_GATE,
	RUNNING,
	COMPLETED,
	FAILED,
	CANCELLED
}
