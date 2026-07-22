package ai.moeru.airicraft.agent.reflex;

import java.util.Map;

public record SurvivalReflexEvent(String type, Map<String, Object> payload) {
	public SurvivalReflexEvent {
		payload = payload == null ? Map.of() : Map.copyOf(payload);
	}
}
