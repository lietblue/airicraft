package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.List;
import java.util.Objects;

public record WorldTaskRequest(
	String taskId,
	String sourceJobId,
	WorldTaskType type,
	GoalSnapshot goal,
	CraftRecipeStepArgs craftRecipe,
	DropItemsStepArgs dropItems,
	EntityInteractionStepArgs entityInteraction,
	SmeltItemsStepArgs smeltItems,
	CollectSmeltedItemsStepArgs collectSmeltedItems,
	ReturnToSurfaceStepArgs returnToSurface,
	BlockPlacementStepArgs blockPlacement,
	BlockUseStepArgs blockUse,
	BlockBreakStepArgs blockBreak,
	UnderwaterHarvestStepArgs underwaterHarvest,
	List<GoalPosition> pickupSweepPositions,
	boolean mineGoalSatisfied
) {
	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe, DropItemsStepArgs dropItems, EntityInteractionStepArgs entityInteraction, SmeltItemsStepArgs smeltItems, CollectSmeltedItemsStepArgs collectSmeltedItems, ReturnToSurfaceStepArgs returnToSurface, BlockPlacementStepArgs blockPlacement, BlockUseStepArgs blockUse, BlockBreakStepArgs blockBreak, GoalPosition pickupSweepPosition) {
		this(taskId, sourceJobId, type, goal, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, null, pickupSweepPosition == null ? List.of() : List.of(pickupSweepPosition), false);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe, DropItemsStepArgs dropItems, EntityInteractionStepArgs entityInteraction, SmeltItemsStepArgs smeltItems, CollectSmeltedItemsStepArgs collectSmeltedItems, ReturnToSurfaceStepArgs returnToSurface, BlockPlacementStepArgs blockPlacement, BlockUseStepArgs blockUse, BlockBreakStepArgs blockBreak) {
		this(taskId, sourceJobId, type, goal, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, null, List.of(), false);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal) {
		this(taskId, sourceJobId, type, goal, null, null, null, null, null, null, null, null, null, null);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe) {
		this(taskId, sourceJobId, type, goal, craftRecipe, null, null, null, null, null, null, null, null, null);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe, DropItemsStepArgs dropItems) {
		this(taskId, sourceJobId, type, goal, craftRecipe, dropItems, null, null, null, null, null, null, null);
	}

	public WorldTaskRequest {
		taskId = normalizedValue(taskId, "taskId");
		sourceJobId = normalizedValue(sourceJobId, "sourceJobId");
		type = Objects.requireNonNull(type, "type");
		pickupSweepPositions = List.copyOf(pickupSweepPositions == null ? List.of() : pickupSweepPositions);
		if (type == WorldTaskType.CRAFT_RECIPE) {
			craftRecipe = Objects.requireNonNull(craftRecipe, "craftRecipe");
		}
		else if (type == WorldTaskType.DROP_ITEMS) {
			dropItems = Objects.requireNonNull(dropItems, "dropItems");
		}
		else if (type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY) {
			entityInteraction = Objects.requireNonNull(entityInteraction, "entityInteraction");
		}
		else if (type == WorldTaskType.SMELT_ITEMS) {
			smeltItems = Objects.requireNonNull(smeltItems, "smeltItems");
		}
		else if (type == WorldTaskType.COLLECT_SMELTED_ITEMS) {
			collectSmeltedItems = Objects.requireNonNull(collectSmeltedItems, "collectSmeltedItems");
		}
		else if (type == WorldTaskType.RETURN_TO_SURFACE) {
			returnToSurface = Objects.requireNonNull(returnToSurface, "returnToSurface");
		}
		else if (type == WorldTaskType.PLACE_BLOCK) {
			blockPlacement = Objects.requireNonNull(blockPlacement, "blockPlacement");
		}
		else if (type == WorldTaskType.USE_BLOCK) {
			blockUse = Objects.requireNonNull(blockUse, "blockUse");
		}
		else if (type == WorldTaskType.BREAK_BLOCKS) {
			blockBreak = Objects.requireNonNull(blockBreak, "blockBreak");
		}
		else if (type == WorldTaskType.UNDERWATER_HARVEST) {
			goal = Objects.requireNonNull(goal, "goal");
			underwaterHarvest = Objects.requireNonNull(underwaterHarvest, "underwaterHarvest");
		}
		else {
			goal = Objects.requireNonNull(goal, "goal");
		}
	}

	public static WorldTaskRequest direct(String taskId, GoalSnapshot goal) {
		return new WorldTaskRequest(taskId, taskId, typeFor(goal), goal, null);
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal) {
		return collectMine(taskId, sourceJobId, goal, List.of());
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal, GoalPosition pickupSweepPosition) {
		return collectMine(taskId, sourceJobId, goal, pickupSweepPosition == null ? List.of() : List.of(pickupSweepPosition));
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal, List<GoalPosition> pickupSweepPositions) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.MINE, goal, null, null, null, null, null, null, null, null, null, null, pickupSweepPositions, false);
	}

	public static WorldTaskRequest underwaterHarvest(
		String taskId,
		String sourceJobId,
		GoalSnapshot goal,
		UnderwaterHarvestStepArgs underwaterHarvest
	) {
		return new WorldTaskRequest(
			taskId,
			sourceJobId,
			WorldTaskType.UNDERWATER_HARVEST,
			goal,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			Objects.requireNonNull(underwaterHarvest, "underwaterHarvest"),
			List.of(),
			false
		);
	}

	public static WorldTaskRequest craftRecipe(String taskId, String sourceJobId, CraftRecipeStepArgs craftRecipe) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.CRAFT_RECIPE, null, craftRecipe, null, null, null, null, null, null, null, null);
	}

	public static WorldTaskRequest dropItems(String taskId, String sourceJobId, DropItemsStepArgs dropItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.DROP_ITEMS, null, null, dropItems, null, null, null, null, null, null, null);
	}

	public static WorldTaskRequest attackEntity(String taskId, String sourceJobId, EntityInteractionStepArgs entityInteraction) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.ATTACK_ENTITY, null, null, null, entityInteraction, null, null, null, null, null, null);
	}

	public static WorldTaskRequest useEntity(String taskId, String sourceJobId, EntityInteractionStepArgs entityInteraction) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.USE_ENTITY, null, null, null, entityInteraction, null, null, null, null, null, null);
	}

	public static WorldTaskRequest smeltItems(String taskId, String sourceJobId, SmeltItemsStepArgs smeltItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.SMELT_ITEMS, null, null, null, null, smeltItems, null, null, null, null, null);
	}

	public static WorldTaskRequest collectSmeltedItems(String taskId, String sourceJobId, CollectSmeltedItemsStepArgs collectSmeltedItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.COLLECT_SMELTED_ITEMS, null, null, null, null, null, collectSmeltedItems, null, null, null, null);
	}

	public static WorldTaskRequest returnToSurface(String taskId, String sourceJobId, ReturnToSurfaceStepArgs returnToSurface) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.RETURN_TO_SURFACE, null, null, null, null, null, null, returnToSurface, null, null, null);
	}

	public static WorldTaskRequest placeBlock(String taskId, String sourceJobId, BlockPlacementStepArgs blockPlacement) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.PLACE_BLOCK, null, null, null, null, null, null, null, blockPlacement, null, null);
	}

	public static WorldTaskRequest useBlock(String taskId, String sourceJobId, BlockUseStepArgs blockUse) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.USE_BLOCK, null, null, null, null, null, null, null, null, blockUse, null);
	}

	public static WorldTaskRequest breakBlocks(String taskId, String sourceJobId, BlockBreakStepArgs blockBreak) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.BREAK_BLOCKS, null, null, null, null, null, null, null, null, null, blockBreak);
	}

	public WorldTaskRequest withPickupSweepPosition(GoalPosition position) {
		return withPickupSweepPositions(position == null ? List.of() : List.of(position));
	}

	public WorldTaskRequest withPickupSweepPositions(List<GoalPosition> positions) {
		return new WorldTaskRequest(taskId, sourceJobId, type, goal, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, underwaterHarvest, positions, mineGoalSatisfied);
	}

	public GoalPosition pickupSweepPosition() {
		return pickupSweepPositions.isEmpty() ? null : pickupSweepPositions.getLast();
	}

	public WorldTaskRequest withMineGoalSatisfied(boolean satisfied) {
		return new WorldTaskRequest(taskId, sourceJobId, type, goal, craftRecipe, dropItems, entityInteraction, smeltItems, collectSmeltedItems, returnToSurface, blockPlacement, blockUse, blockBreak, underwaterHarvest, pickupSweepPositions, satisfied);
	}

	private static WorldTaskType typeFor(GoalSnapshot goal) {
		GoalType goalType = Objects.requireNonNull(goal, "goal").type();
		return switch (goalType) {
			case FOLLOW_PLAYER -> WorldTaskType.FOLLOW;
			case NAVIGATE_TO -> WorldTaskType.NAVIGATE;
			case MINE_BLOCKS -> WorldTaskType.MINE;
		};
	}

	private static String normalizedValue(String value, String fieldName) {
		String trimmed = value == null ? null : value.trim();
		if (trimmed == null || trimmed.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return trimmed;
	}
}
