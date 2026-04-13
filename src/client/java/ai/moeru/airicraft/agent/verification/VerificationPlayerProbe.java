package ai.moeru.airicraft.agent.verification;

import java.util.LinkedHashMap;
import java.util.Map;

public record VerificationPlayerProbe(
	double x,
	double y,
	double z,
	float health,
	float maxHealth,
	int food,
	float saturation,
	boolean onGround,
	double fallDistance,
	String gameMode,
	String dimensionId
) {
	public Map<String, Object> asMap() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("x", x);
		payload.put("y", y);
		payload.put("z", z);
		payload.put("health", health);
		payload.put("maxHealth", maxHealth);
		payload.put("food", food);
		payload.put("saturation", saturation);
		payload.put("onGround", onGround);
		payload.put("fallDistance", fallDistance);
		payload.put("gameMode", gameMode);
		payload.put("dimensionId", dimensionId);
		return Map.copyOf(payload);
	}
}
