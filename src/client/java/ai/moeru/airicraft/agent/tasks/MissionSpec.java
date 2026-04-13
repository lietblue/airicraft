package ai.moeru.airicraft.agent.tasks;

public record MissionSpec(
	String missionId,
	MissionType missionType,
	String goalText
) {
}
