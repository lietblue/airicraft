package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record CraftingOpportunitySnapshot(
	List<CraftingOpportunity> availableCrafts,
	List<CraftingOpportunity> knownCrafts
) {
	public CraftingOpportunitySnapshot {
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
		knownCrafts = knownCrafts == null ? List.of() : List.copyOf(knownCrafts);
	}

	public static CraftingOpportunitySnapshot empty() {
		return new CraftingOpportunitySnapshot(List.of(), List.of());
	}
}
