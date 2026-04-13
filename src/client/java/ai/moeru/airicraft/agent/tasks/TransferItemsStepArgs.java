package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record TransferItemsStepArgs(
	String direction,
	List<String> itemFilters,
	int quantity,
	String containerRef
) {
	public TransferItemsStepArgs {
		itemFilters = itemFilters == null ? List.of() : List.copyOf(itemFilters);
		if (direction == null || direction.isBlank()) {
			throw new IllegalArgumentException("direction must not be blank");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
