package ai.moeru.airicraft.agent.actions;

import java.util.List;

/**
 * Immutable block-harvest knowledge compiled from a loaded loot table and block/tool metadata.
 */
public record BlockAcquisitionRule(
	String blockId,
	String outputItemId,
	List<String> usableToolItemIds,
	boolean emptyHandAllowed,
	boolean probabilistic,
	String lootTableId
) {
	public BlockAcquisitionRule {
		blockId = requireId(blockId, "blockId");
		outputItemId = requireId(outputItemId, "outputItemId");
		usableToolItemIds = usableToolItemIds == null ? List.of() : usableToolItemIds.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.sorted()
			.toList();
		lootTableId = requireId(lootTableId, "lootTableId");
		if (!emptyHandAllowed && usableToolItemIds.isEmpty()) {
			throw new IllegalArgumentException("A block acquisition rule must allow an empty hand or at least one tool");
		}
	}

	private static String requireId(String value, String fieldName) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return normalized;
	}
}
