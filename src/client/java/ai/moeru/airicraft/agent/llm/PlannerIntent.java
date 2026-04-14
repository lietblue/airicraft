package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskSpec;

public record PlannerIntent(
	String type,
	GoalType goalType,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
	TaskSpec taskSpec,
	TaskLedger taskLedger,
	ActiveJobProposal activeJob
) {
	public PlannerIntent(String type, GoalType goalType, String targetPlayer) {
		this(type, goalType, targetPlayer, null, null, null, null, null);
	}

	public PlannerIntent(String type, GoalType goalType, String targetPlayer, GoalPosition position, GoalMineSpec mineSpec) {
		this(type, goalType, targetPlayer, position, mineSpec, null, null, null);
	}

	public PlannerIntent(String type, GoalType goalType, String targetPlayer, GoalPosition position, GoalMineSpec mineSpec, TaskSpec taskSpec) {
		this(type, goalType, targetPlayer, position, mineSpec, taskSpec, null, null);
	}

	public PlannerIntent(
		String type,
		GoalType goalType,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
		TaskSpec taskSpec,
		TaskLedger taskLedger
	) {
		this(type, goalType, targetPlayer, position, mineSpec, taskSpec, taskLedger, null);
	}

	public PlannerIntent(String type, ActiveJobProposal activeJob) {
		this(type, null, null, null, null, null, null, activeJob);
	}

	public PlannerIntent(
		String type,
		GoalType goalType,
		String targetPlayer,
		GoalPosition position,
		GoalMineSpec mineSpec,
		TaskSpec taskSpec,
		TaskLedger taskLedger,
		ActiveJobProposal activeJob
	) {
		this.type = type;
		this.goalType = goalType;
		this.targetPlayer = targetPlayer;
		this.position = position;
		this.mineSpec = mineSpec;
		this.taskSpec = taskSpec;
		this.taskLedger = taskLedger;
		this.activeJob = activeJob;
	}
}
