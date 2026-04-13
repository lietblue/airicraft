package ai.moeru.airicraft.agent.events;

import java.util.Locale;

public enum EventPolicyEffect {
	ALLOW,
	IGNORE,
	SEMANTIC_ONLY,
	TRIGGER_ONLY;

	public static EventPolicyEffect parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
