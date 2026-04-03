package ai.moeru.airicraft.agent.llm;

public record PlannerSessionSnapshot(
	long generation,
	PlannerSessionPhase phase,
	int attemptCount,
	boolean retryPending,
	long retryReadyAtMs,
	PlannerRequest request
) {
}
