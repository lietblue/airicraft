package ai.moeru.airicraft.agent.tasks;

public record CraftRecipeStepArgs(
	String recipeId,
	int quantity
) {
	public CraftRecipeStepArgs {
		if (recipeId == null || recipeId.isBlank()) {
			throw new IllegalArgumentException("recipeId must not be blank");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
