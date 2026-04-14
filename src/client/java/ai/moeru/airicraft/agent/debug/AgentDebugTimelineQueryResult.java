package ai.moeru.airicraft.agent.debug;

import java.util.List;
import java.util.Objects;

public record AgentDebugTimelineQueryResult(
	long oldestEntryId,
	long latestEntryId,
	boolean truncated,
	List<AgentDebugTimelineEntry> entries
) {
	public AgentDebugTimelineQueryResult {
		entries = List.copyOf(Objects.requireNonNullElse(entries, List.of()));
	}
}
