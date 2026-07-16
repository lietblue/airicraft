package ai.moeru.airicraft.agent.reflex;

import java.util.List;

public record SurvivalReflexSnapshot(
	SurvivalReflexState state,
	SurvivalReflexCause cause,
	SurvivalReflexAction action,
	long safetyEpoch,
	String holdId,
	String interruptedJobId,
	String interruptedActionExecutionId,
	List<ThreatSnapshot> threats,
	Float health,
	Float maxHealth,
	Integer air,
	Integer maxAir,
	long startedTick,
	long lastDangerTick,
	int breathableTicks,
	String lastActuatorFailure
) {
	public SurvivalReflexSnapshot {
		state = state == null ? SurvivalReflexState.IDLE : state;
		threats = threats == null ? List.of() : List.copyOf(threats);
	}

	public static SurvivalReflexSnapshot idle() {
		return new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, 0L, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
	}

	public boolean ownsActuation() {
		return state == SurvivalReflexState.ACTIVE;
	}

	public boolean holdsNormalTasks() {
		return state == SurvivalReflexState.ACTIVE || state == SurvivalReflexState.AWAITING_PLANNER;
	}

	public record ThreatSnapshot(
		String uuid,
		String name,
		String entityTypeId,
		double distance,
		boolean alive,
		boolean lineOfSight
	) {
	}
}
