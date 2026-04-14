package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record TaskTerminalEvent(
	String taskId,
	GoalSnapshot goal,
	TaskExecutionState terminalState,
	String message,
	TaskTerminationCause terminationCause
) {
}
