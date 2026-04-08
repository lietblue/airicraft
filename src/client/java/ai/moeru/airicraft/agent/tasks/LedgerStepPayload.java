package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;

public record LedgerStepPayload(
	CollectResourceStepArgs collectResource,
	GoalPosition navigateToPosition,
	NavigateToBlockKindStepArgs navigateToBlockKind,
	GoalMineSpec mineBlocks,
	CraftRecipeStepArgs craftRecipe,
	OpenContainerStepArgs openContainer,
	TransferItemsStepArgs transferItems,
	PlaceBlockStepArgs placeBlock,
	DropItemsStepArgs dropItems,
	WaitStepArgs waitStep,
	AskUserStepArgs askUser,
	FinishStepArgs finish
) {
}
