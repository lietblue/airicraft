package ai.moeru.airicraft.agent.tasks;

public interface CraftingController {
	CraftingAttemptResult craft(String recipeId, boolean craftAll);
}
