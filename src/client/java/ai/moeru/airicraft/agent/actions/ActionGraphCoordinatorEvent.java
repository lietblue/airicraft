package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionGraphCoordinatorEvent(String type, String executionId, Map<String, Object> payload) {
	public ActionGraphCoordinatorEvent {
		type = type == null ? "" : type;
		executionId = executionId == null ? "" : executionId;
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
