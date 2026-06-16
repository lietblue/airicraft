package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActionGraphPrimitiveMapper {
	private ActionGraphPrimitiveMapper() {
	}

	public static ActionGraphPrimitiveDispatch map(ActionPlanStep step, List<CraftingOpportunity> craftingOpportunities) {
		return map(step, craftingOpportunities, List.of());
	}

	public static ActionGraphPrimitiveDispatch map(
		ActionPlanStep step,
		List<CraftingOpportunity> craftingOpportunities,
		List<SmeltingOption> smeltingOptions
	) {
		if (step == null) {
			return ActionGraphPrimitiveDispatch.failed("missing_step", "No executable action graph step was selected", null);
		}
		if (step.kind() != ActionStepKind.PRIMITIVE) {
			return ActionGraphPrimitiveDispatch.failed("unsupported_step_kind", "Only primitive steps can be dispatched", step);
		}
		return switch (step.targetId()) {
			case "collect_resource" -> collectResource(step);
			case "craft_item" -> craftItem(step, craftingOpportunities);
			case "smelt_item" -> smeltItem(step, smeltingOptions);
			case "collect_smelted_item" -> collectSmeltedItem(step);
			case "mine_block" -> mineBlock(step);
			case "pathfind_to" -> pathfindTo(step);
			default -> ActionGraphPrimitiveDispatch.failed(
				"unsupported_primitive",
				"Primitive \"" + step.targetId() + "\" is not executable by the action graph debug dispatcher",
				step
			);
		};
	}

	private static ActionGraphPrimitiveDispatch collectResource(ActionPlanStep step) {
		String resourceKindValue = stringArg(step, "resourceKind");
		int quantity = intArg(step, "quantity", 1);
		TaskResourceKind resourceKind;
		try {
			resourceKind = TaskResourceKind.valueOf(resourceKindValue);
		}
		catch (RuntimeException exception) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "collect_resource requires a supported resourceKind", step);
		}
		if (quantity < 1) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "collect_resource quantity must be positive", step);
		}
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "COLLECT_RESOURCE");
		payload.put("resourceKind", resourceKind.name());
		payload.put("quantity", quantity);
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, resourceKind, quantity)),
			payload
		);
	}

	private static ActionGraphPrimitiveDispatch craftItem(ActionPlanStep step, List<CraftingOpportunity> craftingOpportunities) {
		String itemId = stringArg(step, "itemId");
		String recipeId = stringArg(step, "recipeId");
		int quantity = intArg(step, "quantity", 1);
		if (itemId.isBlank()) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "craft_item requires itemId", step);
		}
		if (quantity < 1) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "craft_item quantity must be positive", step);
		}
		CraftingOpportunity opportunity = safeCraftingOpportunities(craftingOpportunities).stream()
			.filter(candidate -> itemId.equals(candidate.outputItemId()))
			.filter(candidate -> recipeId.isBlank() || recipeId.equals(candidate.recipeId()))
			.min(Comparator.comparing(CraftingOpportunity::recipeId))
			.orElse(null);
		if (opportunity == null) {
			String detail = recipeId.isBlank() ? itemId : itemId + " with recipe " + recipeId;
			return ActionGraphPrimitiveDispatch.failed("recipe_not_found", "No currently craftable recipe produces " + detail, step);
		}
		int times = Math.max(1, (int) Math.ceil(quantity / (double) opportunity.outputCount()));
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "CRAFT_RECIPE");
		payload.put("recipeId", opportunity.recipeId());
		payload.put("outputItemId", opportunity.outputItemId());
		payload.put("outputCount", opportunity.outputCount());
		payload.put("requestedItemCount", quantity);
		payload.put("times", times);
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.craftRecipe(new CraftRecipeStepArgs(opportunity.recipeId(), times)),
			payload
		);
	}

	private static ActionGraphPrimitiveDispatch smeltItem(ActionPlanStep step, List<SmeltingOption> smeltingOptions) {
		String itemId = stringArg(step, "itemId");
		String optionId = stringArg(step, "optionId");
		int inputQuantity = intArg(step, "inputQuantity", 1);
		if (itemId.isBlank()) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "smelt_item requires itemId", step);
		}
		if (inputQuantity < 1) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "smelt_item inputQuantity must be positive", step);
		}
		SmeltingOption option = safeSmeltingOptions(smeltingOptions).stream()
			.filter(candidate -> itemId.equals(candidate.outputItemId()))
			.filter(candidate -> optionId.isBlank() || optionId.equals(candidate.optionId()))
			.filter(candidate -> inputQuantity <= candidate.maxInputQuantity())
			.min(Comparator.comparing(SmeltingOption::optionId))
			.orElse(null);
		if (option == null) {
			String detail = optionId.isBlank() ? itemId : itemId + " with option " + optionId;
			return ActionGraphPrimitiveDispatch.failed("smelting_option_not_found", "No current smelting option produces " + detail, step);
		}
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "SMELT_ITEMS");
		payload.put("optionId", option.optionId());
		payload.put("inputItemId", option.inputItemId());
		payload.put("outputItemId", option.outputItemId());
		payload.put("outputCount", option.outputCount());
		payload.put("inputQuantity", inputQuantity);
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.smeltItems(new SmeltItemsStepArgs(option.optionId(), inputQuantity, SmeltingFuelMode.AUTO, null, 0, null)),
			payload
		);
	}

	private static ActionGraphPrimitiveDispatch collectSmeltedItem(ActionPlanStep step) {
		String itemId = stringArg(step, "itemId");
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "COLLECT_SMELTED_ITEMS");
		if (!itemId.isBlank()) {
			payload.put("outputItemId", itemId);
		}
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.collectSmeltedItems(new CollectSmeltedItemsStepArgs(null, null)),
			payload
		);
	}

	private static ActionGraphPrimitiveDispatch mineBlock(ActionPlanStep step) {
		List<String> blockIds = stringListArg(step, "blockIds");
		int quantity = intArg(step, "quantity", 1);
		if (blockIds.isEmpty()) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "mine_block requires blockIds", step);
		}
		if (quantity < 1) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "mine_block quantity must be positive", step);
		}
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "MINE_BLOCKS");
		payload.put("blockIds", blockIds);
		payload.put("quantity", quantity);
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.mineBlocks(new GoalMineSpec(blockIds, quantity)),
			payload
		);
	}

	private static ActionGraphPrimitiveDispatch pathfindTo(ActionPlanStep step) {
		int x = intArg(step, "x", Integer.MIN_VALUE);
		int y = intArg(step, "y", Integer.MIN_VALUE);
		int z = intArg(step, "z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ActionGraphPrimitiveDispatch.failed("invalid_step_args", "pathfind_to requires x, y, and z", step);
		}
		boolean exactY = booleanArg(step, "exactY", false);
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("jobType", "NAVIGATE_TO");
		payload.put("x", x);
		payload.put("y", y);
		payload.put("z", z);
		payload.put("exactY", exactY);
		return ActionGraphPrimitiveDispatch.dispatchable(
			step,
			ActiveJobProposal.navigateTo(new GoalPosition(x, y, z, exactY)),
			payload
		);
	}

	private static List<CraftingOpportunity> safeCraftingOpportunities(List<CraftingOpportunity> opportunities) {
		return opportunities == null ? List.of() : opportunities;
	}

	private static List<SmeltingOption> safeSmeltingOptions(List<SmeltingOption> options) {
		return options == null ? List.of() : options;
	}

	private static String stringArg(ActionPlanStep step, String key) {
		Object value = step.args().get(key);
		return value == null ? "" : String.valueOf(value);
	}

	private static int intArg(ActionPlanStep step, String key, int defaultValue) {
		Object value = step.args().get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text) {
			try {
				return Integer.parseInt(text.trim());
			}
			catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private static boolean booleanArg(ActionPlanStep step, String key, boolean defaultValue) {
		Object value = step.args().get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text) {
			return Boolean.parseBoolean(text.trim());
		}
		return defaultValue;
	}

	private static List<String> stringListArg(ActionPlanStep step, String key) {
		Object value = step.args().get(key);
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream()
			.map(String::valueOf)
			.filter(text -> !text.isBlank())
			.toList();
	}
}
