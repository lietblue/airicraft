package ai.moeru.airicraft.agent.job;

public enum ActiveJobStatus {
	IDLE,
	QUEUED,
	RUNNING,
	BLOCKED,
	COMPLETED,
	FAILED,
	CANCELLED;

	public boolean terminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
