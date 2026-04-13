package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record StepExecutorSnapshot(
	GoalSnapshot currentGoal,
	TaskProgressSnapshot progress,
	TaskStep taskStep,
	TaskOwnership ownership
) {
	public static StepExecutorSnapshot idle() {
		return new StepExecutorSnapshot(null, new TaskProgressSnapshot(0, 0), TaskStep.NONE, TaskOwnership.NONE);
	}
}
