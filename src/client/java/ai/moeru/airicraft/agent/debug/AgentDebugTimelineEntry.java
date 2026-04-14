package ai.moeru.airicraft.agent.debug;

import java.util.Map;
import java.util.Objects;

public record AgentDebugTimelineEntry(
	long entryId,
	long tick,
	long timestampMs,
	String domain,
	String action,
	String summary,
	Map<String, Object> correlation,
	Map<String, Object> payload
) {
	public AgentDebugTimelineEntry {
		domain = domain == null || domain.isBlank() ? "unknown" : domain;
		action = action == null || action.isBlank() ? "unknown" : action;
		summary = summary == null ? "" : summary;
		correlation = Map.copyOf(Objects.requireNonNullElse(correlation, Map.of()));
		payload = Map.copyOf(Objects.requireNonNullElse(payload, Map.of()));
	}
}
