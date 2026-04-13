package ai.moeru.airicraft.agent.llm;

public record PlannerExecutionResult(
	PlannerRequest request,
	PlannerResponse response,
	LlmUsageSnapshot usage,
	LlmFailureType failureType,
	String failureMessage,
	long generation,
	int attempt,
	PlannerSessionPhase phase,
	boolean stale
) {
	public PlannerExecutionResult(
		PlannerRequest request,
		PlannerResponse response,
		LlmUsageSnapshot usage,
		LlmFailureType failureType,
		String failureMessage
	) {
		this(request, response, usage, failureType, failureMessage, 0L, 1, PlannerSessionPhase.PLANNER_REQUEST, false);
	}

	public boolean succeeded() {
		return failureType == null;
	}
}
