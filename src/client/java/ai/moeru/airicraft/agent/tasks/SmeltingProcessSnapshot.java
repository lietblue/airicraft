package ai.moeru.airicraft.agent.tasks;

public record SmeltingProcessSnapshot(
	String processId,
	String optionId,
	SmeltingStationKey stationKey,
	String outputItemId,
	int expectedOutputCount,
	boolean outputReady
) {
}
