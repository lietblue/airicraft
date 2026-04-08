package ai.moeru.airicraft.agent.tasks;

public record TaskProgressSnapshot(
	int collected,
	int remaining
) {
	public static TaskProgressSnapshot of(int collected, int targetQuantity) {
		return new TaskProgressSnapshot(collected, Math.max(0, targetQuantity - collected));
	}
}
