package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record TaskTerminalEvent(
	String taskId,
	GoalSnapshot goal,
	TaskExecutionState terminalState,
	String message,
	TaskTerminationCause terminationCause,
	TaskFailureCode failureCode
) {
	public TaskTerminalEvent(
		String taskId,
		GoalSnapshot goal,
		TaskExecutionState terminalState,
		String message,
		TaskTerminationCause terminationCause
	) {
		this(taskId, goal, terminalState, message, terminationCause,
			terminalState == TaskExecutionState.FAILED ? TaskFailureCode.UNKNOWN : TaskFailureCode.NONE);
	}

	public TaskTerminalEvent {
		failureCode = failureCode == null
			? terminalState == TaskExecutionState.FAILED ? TaskFailureCode.UNKNOWN : TaskFailureCode.NONE
			: failureCode;
	}
}
