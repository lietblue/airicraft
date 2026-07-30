package ai.moeru.airicraft.agent.goals;

import java.util.List;
import java.util.Objects;

public record GoalMineSpec(
	List<String> blockIds,
	int quantity,
	List<String> matchingItemIds,
	List<String> requiredToolItemIds
) {
	public GoalMineSpec(List<String> blockIds, int quantity) {
		this(blockIds, quantity, blockIds, List.of());
	}

	public GoalMineSpec {
		Objects.requireNonNull(blockIds, "blockIds");
		blockIds = normalizedIds(blockIds);
		if (blockIds.isEmpty()) {
			throw new IllegalArgumentException("blockIds must not be empty");
		}
		if (quantity < 1) {
			throw new IllegalArgumentException("quantity must be positive");
		}
		matchingItemIds = normalizedIds(matchingItemIds);
		if (matchingItemIds.isEmpty()) {
			throw new IllegalArgumentException("matchingItemIds must not be empty");
		}
		requiredToolItemIds = normalizedIds(requiredToolItemIds);
	}

	private static List<String> normalizedIds(List<String> values) {
		return values == null ? List.of() : values.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
	}
}
