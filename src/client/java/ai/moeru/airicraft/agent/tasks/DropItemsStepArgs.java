package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record DropItemsStepArgs(
	List<String> itemFilters,
	int quantity
) {
	public DropItemsStepArgs {
		itemFilters = itemFilters == null ? List.of() : List.copyOf(itemFilters);
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
