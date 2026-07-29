package ai.moeru.airicraft.agent.actions;

public record ActionWatchAnchor(
	String worldId,
	String dimension,
	int x,
	int y,
	int z,
	boolean fallback
) {
	public ActionWatchAnchor {
		worldId = worldId == null ? "" : worldId;
		dimension = dimension == null ? "" : dimension;
	}

	public int chunkX() {
		return x >> 4;
	}

	public int chunkZ() {
		return z >> 4;
	}
}
