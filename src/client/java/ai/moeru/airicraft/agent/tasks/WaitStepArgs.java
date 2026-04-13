package ai.moeru.airicraft.agent.tasks;

public record WaitStepArgs(
	long ticks,
	String reason
) {
	public WaitStepArgs {
		if (ticks < 0L) {
			throw new IllegalArgumentException("ticks must not be negative");
		}
	}
}
