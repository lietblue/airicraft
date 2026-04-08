package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;

public record PlannerRequest(
	long tick,
	long timestampMs,
	SessionMode sessionMode,
	String primaryInteractionPlayer,
	GoalSnapshot activeGoal,
	TaskSnapshot activeTask,
	MissionExecutionSnapshot missionExecution,
	String senderName,
	String message,
	String toolResult
) {
}
