package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable observation of block counts in the runtime's nearby-world scan.
 *
 * <p>An unknown snapshot is distinct from an observed snapshot containing no
 * matching blocks. Resolution policy can therefore remain neutral when no
 * observation was supplied while penalizing known scarcity.</p>
 */
public record NearbyBlockAvailability(
	boolean observed,
	Map<String, Integer> counts
) {
	private static final NearbyBlockAvailability UNKNOWN = new NearbyBlockAvailability(false, Map.of());

	public NearbyBlockAvailability {
		if (!observed || counts == null || counts.isEmpty()) {
			counts = Map.of();
		}
		else {
			LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
					continue;
				}
				copy.put(entry.getKey(), Math.max(0, entry.getValue()));
			}
			counts = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
		}
	}

	public static NearbyBlockAvailability unknown() {
		return UNKNOWN;
	}

	public static NearbyBlockAvailability observed(Map<String, Integer> counts) {
		return new NearbyBlockAvailability(true, counts);
	}

	public int count(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return 0;
		}
		return counts.getOrDefault(blockId, 0);
	}
}
