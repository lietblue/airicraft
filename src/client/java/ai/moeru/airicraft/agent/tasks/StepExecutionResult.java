package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

public record StepExecutionResult(
	String stepId,
	StepExecutionStatus status,
	String failureReason,
	Map<String, Object> evidenceDelta,
	Map<String, Object> terminalFacts,
	long updatedTick
) {
	public StepExecutionResult {
		evidenceDelta = evidenceDelta == null ? Map.of() : Map.copyOf(evidenceDelta);
		terminalFacts = terminalFacts == null ? Map.of() : Map.copyOf(terminalFacts);
	}

	public static StepExecutionResult idle() {
		return new StepExecutionResult(null, StepExecutionStatus.IDLE, null, Map.of(), Map.of(), -1L);
	}
}
