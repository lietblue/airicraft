package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.verification.VerificationReport;

public record AgentRuntimeSnapshot(
	boolean initialized,
	long tickCount,
	SessionSnapshot session,
	TaskSnapshot task,
	TaskExecutionSnapshot taskExecution,
	MissionExecutionSnapshot missionExecution,
	VerificationReport verification
) {
}
