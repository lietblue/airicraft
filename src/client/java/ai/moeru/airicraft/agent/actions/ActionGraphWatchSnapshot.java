package ai.moeru.airicraft.agent.actions;

public record ActionGraphWatchSnapshot(
	String executionId,
	String watchId,
	String stepId,
	ActionWatchSpec spec,
	long consumedEligibleTicks,
	boolean progressEligible,
	String pauseReason
) {
	public ActionGraphWatchSnapshot {
		executionId = executionId == null ? "" : executionId;
		watchId = watchId == null ? "" : watchId;
		stepId = stepId == null ? "" : stepId;
		pauseReason = pauseReason == null ? "" : pauseReason;
	}
}
