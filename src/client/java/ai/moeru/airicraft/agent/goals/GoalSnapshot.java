package ai.moeru.airicraft.agent.goals;

public record GoalSnapshot(
	GoalType type,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
	long updatedTick,
	String source
) {
	public GoalSnapshot(GoalType type, String targetPlayer, long updatedTick, String source) {
		this(type, targetPlayer, null, null, updatedTick, source);
	}
}
