package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.TaskSpec;

import java.util.Objects;

public record ActiveJobProposal(
	ActiveJobType type,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
	TaskSpec taskSpec,
	Long waitTicks,
	String askPrompt
) {
	public ActiveJobProposal {
		type = Objects.requireNonNull(type, "type");
		targetPlayer = targetPlayer == null || targetPlayer.isBlank() ? null : targetPlayer;
		waitTicks = waitTicks == null ? null : Math.max(0L, waitTicks);
		askPrompt = askPrompt == null || askPrompt.isBlank() ? null : askPrompt.trim();
	}

	public static ActiveJobProposal followPlayer(String targetPlayer) {
		return new ActiveJobProposal(ActiveJobType.FOLLOW_PLAYER, targetPlayer, null, null, null, null, null);
	}

	public static ActiveJobProposal navigateTo(GoalPosition position) {
		return new ActiveJobProposal(ActiveJobType.NAVIGATE_TO, null, position, null, null, null, null);
	}

	public static ActiveJobProposal mineBlocks(GoalMineSpec mineSpec) {
		return new ActiveJobProposal(ActiveJobType.MINE_BLOCKS, null, null, mineSpec, null, null, null);
	}

	public static ActiveJobProposal collectResource(TaskSpec taskSpec) {
		return new ActiveJobProposal(ActiveJobType.COLLECT_RESOURCE, null, null, null, taskSpec, null, null);
	}

	public static ActiveJobProposal waitFor(long waitTicks) {
		return new ActiveJobProposal(ActiveJobType.WAIT, null, null, null, null, waitTicks, null);
	}

	public static ActiveJobProposal askUser(String askPrompt) {
		return new ActiveJobProposal(ActiveJobType.ASK_USER, null, null, null, null, null, askPrompt);
	}
}
