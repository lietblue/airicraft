package ai.moeru.airicraft.agent.tasks;

public record CraftingAttemptResult(
	boolean accepted,
	String outputItemId,
	String failureReason
) {
	public static CraftingAttemptResult started(String outputItemId) {
		return new CraftingAttemptResult(true, outputItemId, null);
	}

	public static CraftingAttemptResult failed(String failureReason) {
		return new CraftingAttemptResult(false, null, failureReason);
	}
}
