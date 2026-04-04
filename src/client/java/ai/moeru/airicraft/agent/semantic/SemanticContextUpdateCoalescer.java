package ai.moeru.airicraft.agent.semantic;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

public final class SemanticContextUpdateCoalescer {
	private SemanticContextUpdateCoalescer() {
	}

	public static SemanticContextUpdate mergeIfPossible(
		SemanticContextUpdate existing,
		SemanticContextUpdate incoming,
		long anchorTimeMs
	) {
		if (existing == null || incoming == null) {
			return null;
		}
		if (existing.kind() != incoming.kind()) {
			return null;
		}
		if (existing.aggregationKey() == null || incoming.aggregationKey() == null) {
			return null;
		}
		if (!Objects.equals(existing.aggregationKey(), incoming.aggregationKey())) {
			return null;
		}
		if (!Objects.equals(existing.sourceType(), incoming.sourceType())) {
			return null;
		}

		String sourceType = existing.sourceType();
		if (sourceType == null || sourceType.isBlank()) {
			return null;
		}

			Map<String, Object> mergedPayload = new LinkedHashMap<>(existing.payload());
			switch (sourceType) {
				case "pickup.item_picked_up", "crafting.item_crafted" ->
					mergedPayload.put("count", countValue(existing.payload().get("count")) + countValue(incoming.payload().get("count")));
				case "combat.damage_taken" -> {
					mergedPayload.put("amount", floatValue(existing.payload().get("amount")) + floatValue(incoming.payload().get("amount")));
					if (!mergedPayload.containsKey("healthBefore")) {
						mergedPayload.put("healthBefore", incoming.payload().get("healthBefore"));
					}
					mergedPayload.put("healthAfter", incoming.payload().get("healthAfter"));
					mergedPayload.put("fatal", booleanValue(existing.payload().get("fatal")) || booleanValue(incoming.payload().get("fatal")));
				}
				case "social.player_joined_game",
					"social.player_left_game",
					"social.player_joined_nearby",
				"social.player_left_nearby",
				"follow.stuck" -> mergeDistinctValue(mergedPayload, "player", incoming.payload().get("player"));
			case "planner.reset_requested", "planner.degraded_entered", "planner.degraded_cleared" -> {
				// Nothing else to merge beyond event counts and timestamps.
			}
			default -> {
				return null;
			}
		}

		long tick = Math.max(existing.tick(), incoming.tick());
		long timestampMs = Math.max(existing.timestampMs(), incoming.timestampMs());
		int sourceEventCount = existing.sourceEventCount() + incoming.sourceEventCount();
		long firstSourceSeqNo = minPositive(existing.firstSourceSeqNo(), incoming.firstSourceSeqNo());
		long lastSourceSeqNo = Math.max(existing.lastSourceSeqNo(), incoming.lastSourceSeqNo());
		AggregatedSemanticEvent aggregatedEvent = new AggregatedSemanticEvent(
			sourceType,
			Map.copyOf(mergedPayload),
			tick,
			timestampMs,
			sourceEventCount,
			firstSourceSeqNo,
			lastSourceSeqNo
		);
		String text = SemanticContextUpdateFormatter.format(aggregatedEvent, anchorTimeMs);
		if (text == null || text.isBlank()) {
			return null;
		}

		return new SemanticContextUpdate(
			existing.kind(),
			text,
			tick,
			timestampMs,
			sourceEventCount,
			firstSourceSeqNo,
			lastSourceSeqNo,
			sourceType,
			mergedPayload,
			existing.aggregationKey()
		);
	}

	private static int countValue(Object value) {
		if (value instanceof Number number) {
			return Math.max(1, number.intValue());
		}
		if (value == null) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(String.valueOf(value)));
		}
		catch (NumberFormatException ignored) {
			return 1;
		}
	}

	private static float floatValue(Object value) {
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value == null) {
			return 0.0F;
		}
		try {
			return Float.parseFloat(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return 0.0F;
		}
	}

	private static boolean booleanValue(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		return value != null && Boolean.parseBoolean(String.valueOf(value));
	}

	private static void mergeDistinctValue(Map<String, Object> payload, String key, Object candidate) {
		if (candidate == null || String.valueOf(candidate).isBlank()) {
			return;
		}
		Object existing = payload.get(key);
		if (existing == null || String.valueOf(existing).isBlank()) {
			payload.put(key, candidate);
			return;
		}
		if (String.valueOf(existing).equals(String.valueOf(candidate))) {
			return;
		}
		LinkedHashSet<String> values = new LinkedHashSet<>();
		values.add(String.valueOf(existing));
		values.add(String.valueOf(candidate));
		payload.put(key, String.join(", ", values));
	}

	private static long minPositive(long left, long right) {
		if (left <= 0L) {
			return Math.max(0L, right);
		}
		if (right <= 0L) {
			return left;
		}
		return Math.min(left, right);
	}
}
