package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record TaskExecutionSnapshot(
	TaskExecutionState state,
	String taskId,
	GoalSnapshot activeGoal,
	String processName,
	String lastPathEvent,
	Double estimatedTicksToGoal,
	TaskTerminationCause terminationCause
) {
	public static TaskExecutionSnapshot idle() {
		return new TaskExecutionSnapshot(TaskExecutionState.IDLE, null, null, null, null, null, null);
	}
}
