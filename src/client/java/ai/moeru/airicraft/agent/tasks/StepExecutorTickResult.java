package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

public record StepExecutorTickResult(
	TaskState taskState,
	GoalSnapshot currentGoal,
	TaskProgressSnapshot progress,
	TaskStep taskStep,
	TaskOwnership ownership,
	StepExecutionResult stepResult
) {
}
