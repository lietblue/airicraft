package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record SmeltingOpportunitySnapshot(
	List<SmeltingOption> availableSmelts,
	List<SmeltingRecipeKnowledge> knownSmelts
) {
	public SmeltingOpportunitySnapshot {
		availableSmelts = availableSmelts == null ? List.of() : List.copyOf(availableSmelts);
		knownSmelts = knownSmelts == null ? List.of() : List.copyOf(knownSmelts);
	}

	public static SmeltingOpportunitySnapshot empty() {
		return new SmeltingOpportunitySnapshot(List.of(), List.of());
	}
}
