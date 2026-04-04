package ai.moeru.airicraft.agent.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record EventPolicyMatch(
	String eventType,
	String player,
	String speaker,
	String actor,
	String itemId,
	String damageTypeId,
	String attackerName
) {
	public EventPolicyMatch {
		eventType = normalize(eventType);
		player = normalize(player);
		speaker = normalize(speaker);
		actor = normalize(actor);
		itemId = normalize(itemId);
		damageTypeId = normalize(damageTypeId);
		attackerName = normalize(attackerName);
	}

	public boolean isValid() {
		return eventType != null;
	}

	public boolean matches(SemanticEvent event) {
		Objects.requireNonNull(event, "event");
		if (eventType == null || !eventType.equals(event.type())) {
			return false;
		}
		return matchesField(event, "player", player)
			&& matchesField(event, "speaker", speaker)
			&& matchesField(event, "actor", actor)
			&& matchesField(event, "itemId", itemId)
			&& matchesField(event, "damageTypeId", damageTypeId)
			&& matchesField(event, "attackerName", attackerName);
	}

	public Map<String, Object> toPayload() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		put(payload, "eventType", eventType);
		put(payload, "player", player);
		put(payload, "speaker", speaker);
		put(payload, "actor", actor);
		put(payload, "itemId", itemId);
		put(payload, "damageTypeId", damageTypeId);
		put(payload, "attackerName", attackerName);
		return Map.copyOf(payload);
	}

	private static boolean matchesField(SemanticEvent event, String key, String expected) {
		if (expected == null) {
			return true;
		}
		String actual = payloadString(event, key);
		return expected.equals(actual);
	}

	private static String payloadString(SemanticEvent event, String key) {
		Object direct = event.payload().get(key);
		if (direct != null) {
			return String.valueOf(direct);
		}
		if ("speaker".equals(key)) {
			Object player = event.payload().get("player");
			if (player != null) {
				return String.valueOf(player);
			}
			Object actor = event.payload().get("actor");
			if (actor != null) {
				return String.valueOf(actor);
			}
			if ("social.system_message".equals(event.type())) {
				return "server";
			}
		}
		return null;
	}

	private static void put(Map<String, Object> payload, String key, String value) {
		if (value != null) {
			payload.put(key, value);
		}
	}

	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
