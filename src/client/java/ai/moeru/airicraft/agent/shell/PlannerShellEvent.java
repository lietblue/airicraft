package ai.moeru.airicraft.agent.shell;

public record PlannerShellEvent(
	long timestampMs,
	String kind,
	long generation,
	int attempt,
	String phase,
	String detail
) {
}
