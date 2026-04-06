package ai.moeru.airicraft.agent.goals;

import java.util.List;
import java.util.Objects;

public record GoalMineSpec(
	List<String> blockIds,
	int quantity
) {
	public GoalMineSpec {
		Objects.requireNonNull(blockIds, "blockIds");
		blockIds = List.copyOf(blockIds);
		if (blockIds.isEmpty()) {
			throw new IllegalArgumentException("blockIds must not be empty");
		}
		if (quantity < 1) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
