package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record NavigateToBlockKindStepArgs(
	List<String> blockIds
) {
	public NavigateToBlockKindStepArgs {
		blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
		if (blockIds.isEmpty()) {
			throw new IllegalArgumentException("blockIds must not be empty");
		}
	}
}
