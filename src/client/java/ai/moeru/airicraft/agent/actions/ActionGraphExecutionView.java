package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionGraphExecutionView(
	ActionGraphResidency residency,
	long creationOrder,
	long createdTick,
	long updatedTick,
	ActionGraphExecutionSnapshot execution
) {
	public Map<String, Object> toPayload(boolean verbose) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>(execution.toPayload(verbose));
		payload.put("residency", residency.name());
		payload.put("creationOrder", creationOrder);
		payload.put("createdTick", createdTick);
		payload.put("updatedTick", updatedTick);
		return payload;
	}
}
