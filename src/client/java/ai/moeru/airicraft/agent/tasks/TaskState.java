package ai.moeru.airicraft.agent.tasks;

public enum TaskState {
	IDLE,
	QUEUED,
	RUNNING,
	WAITING_FOR_PICKUP,
	PAUSED_BY_SESSION_GATE,
	PAUSED_BY_REFLEX,
	COMPLETED,
	FAILED,
	CANCELLED
}
