package ai.moeru.airicraft.agent.llm;

public record StalePlannerRejection(
	long generation,
	long requestSafetyEpoch,
	long currentSafetyEpoch,
	String requestHoldId,
	String currentHoldId,
	String phase
) {
}
