package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionMode;

public record PlannerRequestSeed(
	long tick,
	long timestampMs,
	SessionMode sessionMode,
	String primaryInteractionPlayer,
	GoalSnapshot activeGoal
) {
	public PlannerRequest toPlannerRequest() {
		return new PlannerRequest(tick, timestampMs, sessionMode, primaryInteractionPlayer, activeGoal, (PlannerTriggerBatch) null, null);
	}

	public static PlannerRequestSeed fromRequest(PlannerRequest request) {
		if (request == null) {
			return null;
		}
		return new PlannerRequestSeed(
			request.tick(),
			request.timestampMs(),
			request.sessionMode(),
			request.primaryInteractionPlayer(),
			request.activeGoal()
		);
	}
}
