package ai.moeru.airicraft.agent.tasks;

public record SmeltingRecipeKnowledge(
	String optionId,
	String inputItemId,
	String outputItemId,
	int outputCount,
	int maxInputQuantity,
	int cookTimeTicks,
	String stationItemId,
	int stationItemCount
) {
	public SmeltingRecipeKnowledge {
		if (optionId == null || optionId.isBlank()) {
			throw new IllegalArgumentException("optionId must not be blank");
		}
		if (inputItemId == null || inputItemId.isBlank()) {
			throw new IllegalArgumentException("inputItemId must not be blank");
		}
		if (outputItemId == null || outputItemId.isBlank()) {
			throw new IllegalArgumentException("outputItemId must not be blank");
		}
		if (stationItemId == null || stationItemId.isBlank()) {
			throw new IllegalArgumentException("stationItemId must not be blank");
		}
		outputCount = Math.max(1, outputCount);
		maxInputQuantity = Math.max(1, maxInputQuantity);
		cookTimeTicks = Math.max(1, cookTimeTicks);
		stationItemCount = Math.max(1, stationItemCount);
	}
}
