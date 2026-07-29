package ai.moeru.airicraft.agent.actions;

public record ActionGraphAgentPosition(String worldId, String dimension, int x, int y, int z) {
	public ActionGraphAgentPosition {
		worldId = worldId == null ? "" : worldId;
		dimension = dimension == null ? "" : dimension;
	}
}
