package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record TaskExecutionSnapshot(
	TaskExecutionState state,
	GoalSnapshot activeGoal,
	String processName,
	String lastPathEvent,
	Double estimatedTicksToGoal
) {
	public static TaskExecutionSnapshot idle() {
		return new TaskExecutionSnapshot(TaskExecutionState.IDLE, null, null, null, null);
	}
}
