package ai.moeru.airicraft.agent.motor;

import java.util.LinkedHashMap;
import java.util.Map;

public record MotorShadowEvent(String type, Map<String, Object> payload) {
	public MotorShadowEvent {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type must not be blank");
		}
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
