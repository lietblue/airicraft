package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.MinedBlockDropMapper;
import ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ActionResolver {
	private static final int DEFAULT_MAX_DEPTH = 8;
	private static final int DEFAULT_SMELT_COOK_TICKS = 200;
	private static final int FUEL_TICKS_PLANKS = 300;
	private static final int FUEL_TICKS_LOGS = 300;
	private static final int FUEL_TICKS_STICKS = 100;
	private static final int FUEL_TICKS_COAL = 1600;
	private static final int RECIPE_INPUT_DEFICIT_COST = 25;
	private static final Set<ActionFactProvenance> GUARD_USABLE_PROVENANCE = Set.of(
		ActionFactProvenance.OBSERVED,
		ActionFactProvenance.EXECUTOR_REPORTED,
		ActionFactProvenance.INFERRED
	);

	private final ActionsetIndex index;
	private final ActionFactStore facts;
	private final ActionResolverContext context;
	private final int maxDepth;
	private final Set<String> blockedAlternativeKeys;
	private final boolean preferActionsetRoutes;

	public ActionResolver(ActionsetIndex index, ActionFactStore facts, ActionResolverContext context) {
		this(index, facts, context, DEFAULT_MAX_DEPTH);
	}

	public ActionResolver(ActionsetIndex index, ActionFactStore facts, ActionResolverContext context, int maxDepth) {
		this(index, facts, context, maxDepth, Set.of());
	}

	public ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		ActionResolverContext context,
		int maxDepth,
		Set<String> blockedAlternativeKeys
	) {
		this(index, facts, context, maxDepth, blockedAlternativeKeys, false);
	}

	public ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		ActionResolverContext context,
		int maxDepth,
		Set<String> blockedAlternativeKeys,
		boolean preferActionsetRoutes
	) {
		this.index = Objects.requireNonNull(index, "index");
		this.facts = Objects.requireNonNull(facts, "facts");
		this.context = Objects.requireNonNull(context, "context");
		this.maxDepth = Math.max(1, maxDepth);
		this.blockedAlternativeKeys = blockedAlternativeKeys == null ? Set.of() : Set.copyOf(blockedAlternativeKeys);
		this.preferActionsetRoutes = preferActionsetRoutes;
	}

	public ActionResolveResult resolve(ActionGoal goal) {
		ArrayList<ActionTraceEvent> trace = new ArrayList<>();
		Optional<ActionRoute> route = resolveGoal(goal, 0, new LinkedHashSet<>(), trace);
		if (route.isPresent()) {
			trace.add(event("goal_succeeded", "", "", "", Map.of("goal", goal.normalizedKey())));
			return ActionResolveResult.success(route.get(), trace);
		}
		trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "no_route")));
		return ActionResolveResult.failure("no_route", "no route can satisfy " + goal.normalizedKey(), trace);
	}

	private Optional<ActionRoute> resolveGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		trace.add(event("goal_started", "", "", "", Map.of("goal", goal.normalizedKey(), "depth", depth)));
		if (depth > maxDepth) {
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "max_depth_exceeded")));
			return Optional.empty();
		}
		if (!resolving.add(goal.normalizedKey())) {
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "cycle_detected")));
			return Optional.empty();
		}

		if (goalSatisfied(goal, trace)) {
			resolving.remove(goal.normalizedKey());
			return Optional.of(ActionRoute.empty());
		}

		if (!preferActionsetRoutes) {
			Optional<ActionRoute> resourceRoute = resolveResourceProviderGoal(goal, depth, resolving, trace);
			if (resourceRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return resourceRoute;
			}
			Optional<ActionRoute> logItemRoute = resolveLogItemProviderGoal(goal, trace);
			if (logItemRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return logItemRoute;
			}
			Optional<ActionRoute> smeltingRoute = resolveSmeltingProviderGoal(goal, depth, resolving, trace);
			if (smeltingRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return smeltingRoute;
			}
			Optional<ActionRoute> miningRoute = resolveMiningProviderGoal(goal, depth, resolving, trace);
			if (miningRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return miningRoute;
			}
			Optional<ActionRoute> providerRoute = resolveRecipeProviderGoal(goal, depth, resolving, trace);
			if (providerRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return providerRoute;
			}
		}

		Optional<ActionRoute> actionsetRoute = resolveActionsetGoal(goal, depth, resolving, trace);
		if (actionsetRoute.isPresent()) {
			resolving.remove(goal.normalizedKey());
			return actionsetRoute;
		}

		if (preferActionsetRoutes) {
			Optional<ActionRoute> resourceRoute = resolveResourceProviderGoal(goal, depth, resolving, trace);
			if (resourceRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return resourceRoute;
			}
			Optional<ActionRoute> logItemRoute = resolveLogItemProviderGoal(goal, trace);
			if (logItemRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return logItemRoute;
			}
			Optional<ActionRoute> smeltingRoute = resolveSmeltingProviderGoal(goal, depth, resolving, trace);
			if (smeltingRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return smeltingRoute;
			}
			Optional<ActionRoute> miningRoute = resolveMiningProviderGoal(goal, depth, resolving, trace);
			if (miningRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return miningRoute;
			}
			Optional<ActionRoute> providerRoute = resolveRecipeProviderGoal(goal, depth, resolving, trace);
			if (providerRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return providerRoute;
			}
		}

		resolving.remove(goal.normalizedKey());
		return Optional.empty();
	}

	private Optional<ActionRoute> resolveActionsetGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		for (ActionsetEntry entry : matchingActionsets(goal)) {
			Map<String, Integer> params = bindParams(entry.definition(), goal);
			for (Map<String, Object> alternative : alternatives(entry)) {
				String alternativeId = scalar(alternative.get("id"), "<unnamed>");
				if (blockedAlternativeKeys.contains(entry.actionId() + ":" + alternativeId)) {
					trace.add(event(
						"route_candidate_blocked",
						entry.actionId(),
						alternativeId,
						"",
						Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
					));
					continue;
				}
				trace.add(event(
					"route_candidate_built",
					entry.actionId(),
					alternativeId,
					"",
					Map.of("goal", goal.normalizedKey(), "cost", cost(alternative))
				));

				List<ActionFact> matchedGuards = matchedGuards(alternative, params, trace, entry.actionId(), alternativeId);
				if (matchedGuards == null) {
					continue;
				}

				Optional<ActionRoute> expanded = expandAlternative(entry, alternative, params, matchedGuards, depth, resolving, trace);
				if (expanded.isPresent()) {
					trace.add(event("route_selected", entry.actionId(), alternativeId, "", Map.of("goal", goal.normalizedKey())));
					return expanded;
				}
			}
		}
		return Optional.empty();
	}

	private Optional<ActionRoute> resolveResourceProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return Optional.empty();
		}
		String resourceKind = goal.keys().getOrDefault("resourceKind", "");
		Optional<ResourceGatheringCatalog.ResourceEntry> entry = ResourceGatheringCatalog.entry(resourceKind);
		if (entry.isEmpty()) {
			trace.add(event(
				"route_candidate_rejected",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "unsupported_resource_kind")
			));
			return Optional.empty();
		}
		String alternativeKey = "resource_provider:" + resourceKind;
		if (blockedAlternativeKeys.contains(alternativeKey)) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
			));
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(ActionRoute.empty());
		}
		if (!entry.get().aggregate()) {
			String itemId = entry.get().primaryItemId();
			if (itemId.isBlank()) {
				return Optional.empty();
			}
			trace.add(event(
				"route_candidate_built",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", 35, "resourceKind", resourceKind, "itemId", itemId)
			));
			Optional<ActionRoute> itemRoute = resolveGoal(ActionGoal.inventoryItem(itemId, targetCount), depth + 1, resolving, trace);
			if (itemRoute.isEmpty()) {
				trace.add(event(
					"route_candidate_rejected",
					"resource_provider",
					resourceKind,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "item_route_unavailable", "itemId", itemId)
				));
				return Optional.empty();
			}
			trace.add(event("route_selected", "resource_provider", resourceKind, "", Map.of("goal", goal.normalizedKey(), "itemId", itemId)));
			return itemRoute;
		}
		trace.add(event(
			"route_candidate_built",
			"resource_provider",
			resourceKind,
			"",
			Map.of("goal", goal.normalizedKey(), "cost", 20, "resourceKind", resourceKind)
		));
		LinkedHashMap<String, Object> args = new LinkedHashMap<>();
		args.put("resourceKind", resourceKind);
		args.put("quantity", deficitCount);
		ActionPlanStep step = new ActionPlanStep(ActionStepKind.PRIMITIVE, "resource_provider", resourceKind, "collect_resource", "collect_resource", args);
		trace.add(event("primitive_planned", "resource_provider", resourceKind, "collect_resource", Map.of("primitive", "collect_resource", "resourceKind", resourceKind)));
		trace.add(event("route_selected", "resource_provider", resourceKind, "", Map.of("goal", goal.normalizedKey())));
		return Optional.of(new ActionRoute(List.of(step), 20));
	}

	private Optional<ActionRoute> resolveLogItemProviderGoal(
		ActionGoal goal,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String itemId = goal.keys().getOrDefault("itemId", "");
		if (!ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return Optional.empty();
		}
		String alternativeKey = "resource_provider:" + itemId;
		if (blockedAlternativeKeys.contains(alternativeKey)) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				itemId,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
			));
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(ActionRoute.empty());
		}
		trace.add(event(
			"route_candidate_built",
			"resource_provider",
			itemId,
			"",
			Map.of("goal", goal.normalizedKey(), "cost", 20, "resourceKind", "WOOD_LOGS")
		));
		LinkedHashMap<String, Object> args = new LinkedHashMap<>();
		args.put("resourceKind", "WOOD_LOGS");
		args.put("quantity", deficitCount);
		ActionPlanStep step = new ActionPlanStep(ActionStepKind.PRIMITIVE, "resource_provider", itemId, "collect_resource", "collect_resource", args);
		trace.add(event("primitive_planned", "resource_provider", itemId, "collect_resource", Map.of("primitive", "collect_resource", "resourceKind", "WOOD_LOGS")));
		trace.add(event("route_selected", "resource_provider", itemId, "", Map.of("goal", goal.normalizedKey())));
		return Optional.of(new ActionRoute(List.of(step), 20));
	}

	private Optional<ActionRoute> resolveRecipeProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(ActionRoute.empty());
		}

		Map<String, String> recipeQuery = new LinkedHashMap<>();
		recipeQuery.put("worldId", context.worldId());
		recipeQuery.put("actorId", context.actorId());
		ActionRoute bestRoute = null;
		String bestRecipeId = "";
		for (ActionFact recipe : facts.query(ActionFactType.CRAFT_RECIPE, recipeQuery).stream()
			.filter(this::usableFact)
			.filter(fact -> outputItemId.equals(scalar(fact.payload().get("outputItemId"), "")))
			.sorted(Comparator.comparing(fact -> fact.identity().keys().getOrDefault("recipeId", "")))
			.toList()) {
			String recipeId = recipe.identity().keys().getOrDefault("recipeId", "");
			String alternativeKey = "recipe_provider:" + recipeId;
			if (blockedAlternativeKeys.contains(alternativeKey)) {
				trace.add(event(
					"route_candidate_blocked",
					"recipe_provider",
					recipeId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
				));
				continue;
			}
			Map<String, Integer> inputCounts = recipeInputCounts(recipe);
			if (inputCounts.isEmpty()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int craftTimes = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			String gridKind = scalar(recipe.payload().get("gridKind"), "");
			Map<String, Integer> effectiveInputCounts = effectiveRecipeInputCounts(outputItemId, inputCounts, gridKind);
			int inputDeficitCost = recipeInputDeficit(effectiveInputCounts, craftTimes) * RECIPE_INPUT_DEFICIT_COST;
			trace.add(event(
				"route_candidate_built",
				"recipe_provider",
				recipeId,
				"",
				Map.of(
					"goal", goal.normalizedKey(),
					"cost", 15 + inputDeficitCost,
					"outputItemId", outputItemId,
					"inputDeficitCost", inputDeficitCost
				)
			));

			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 15 + inputDeficitCost;
			boolean inputsResolved = true;
			for (Map.Entry<String, Integer> input : effectiveInputCounts.entrySet()) {
				int requiredCount = input.getValue() * craftTimes;
				if (requiredCount <= 0) {
					continue;
				}
				Optional<ActionRoute> subRoute = resolveRecipeInputGoal(input.getKey(), requiredCount, depth, resolving, trace);
				if (subRoute.isEmpty()) {
					inputsResolved = false;
					break;
				}
				steps.addAll(subRoute.get().steps());
				routeCost += subRoute.get().cost();
			}
			if (!inputsResolved) {
				continue;
			}

			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			args.put("itemId", outputItemId);
			args.put("recipeId", recipeId);
			args.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "recipe_provider", recipeId, "craft_item", "craft_item", args));
			trace.add(event("primitive_planned", "recipe_provider", recipeId, "craft_item", Map.of("primitive", "craft_item", "itemId", outputItemId)));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			if (bestRoute == null || candidateRoute.cost() < bestRoute.cost()) {
				bestRoute = candidateRoute;
				bestRecipeId = recipeId;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		trace.add(event("route_selected", "recipe_provider", bestRecipeId, "", Map.of("goal", goal.normalizedKey())));
		return Optional.of(bestRoute);
	}

	private Map<String, Integer> effectiveRecipeInputCounts(
		String outputItemId,
		Map<String, Integer> inputCounts,
		String gridKind
	) {
		if (!"WORKBENCH_3X3".equals(gridKind)
			|| "minecraft:crafting_table".equals(outputItemId)
			|| existingGoalCount(ActionGoal.inventoryItem("minecraft:crafting_table", 1)) >= 1) {
			return inputCounts;
		}
		LinkedHashMap<String, Integer> effective = new LinkedHashMap<>(inputCounts);
		effective.merge(workbenchSetupPlankItemId(inputCounts), 4, Integer::sum);
		return effective;
	}

	private String workbenchSetupPlankItemId(Map<String, Integer> inputCounts) {
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (inputCounts.containsKey(plankItemId)) {
				return plankItemId;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count >= 4) {
				return plankItemId;
			}
		}
		String bestObservedLogPlank = "";
		int bestObservedLogCount = 0;
		for (int index = 0; index < ActionGraphDomainKnowledge.logItemIds().size(); index++) {
			String logItemId = ActionGraphDomainKnowledge.logItemIds().get(index);
			int count = existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
			if (count > bestObservedLogCount && index < ActionGraphDomainKnowledge.plankItemIds().size()) {
				bestObservedLogPlank = ActionGraphDomainKnowledge.plankItemIds().get(index);
				bestObservedLogCount = count;
			}
		}
		if (!bestObservedLogPlank.isBlank()) {
			return bestObservedLogPlank;
		}
		String bestObservedPlank = "";
		int bestObservedPlankCount = 0;
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count > bestObservedPlankCount) {
				bestObservedPlank = plankItemId;
				bestObservedPlankCount = count;
			}
		}
		if (!bestObservedPlank.isBlank()) {
			return bestObservedPlank;
		}
		return "minecraft:oak_planks";
	}

	private Optional<ActionRoute> resolveRecipeInputGoal(
		String itemId,
		int requiredCount,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (ActionGraphDomainKnowledge.plankItemIds().contains(itemId)) {
			return resolveRecipePlankInputGoal(itemId, requiredCount, depth, resolving, trace);
		}
		if (!ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return resolveGoal(
				ActionGoal.inventoryItem(itemId, requiredCount),
				depth + 1,
				resolving,
				trace
			);
		}
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
		if (exactCount <= 0 && totalWoodLogs > 0) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				itemId,
				"",
				Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_log_variant")
			));
			return Optional.empty();
		}
		int missingExactLogs = requiredCount - exactCount;
		return resolveGoal(
			ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingExactLogs),
			depth + 1,
			resolving,
			trace
		);
	}

	private Optional<ActionRoute> resolveRecipePlankInputGoal(
		String itemId,
		int requiredCount,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		String logItemId = logItemForPlank(itemId);
		int logCount = logItemId.isBlank() ? 0 : existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
		if (logCount > 0) {
			return resolveGoal(
				ActionGoal.inventoryItem(itemId, requiredCount),
				depth + 1,
				resolving,
				trace
			);
		}
		int missingLogs = Math.max(1, (int) Math.ceil((requiredCount - exactCount) / 4.0));
		if (exactCount > 0 || !observedAnyWoodMaterial()) {
			int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
			return resolveGoal(
				ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingLogs),
				depth + 1,
				resolving,
				trace
			);
		}
		trace.add(event(
			"route_candidate_blocked",
			"recipe_provider",
			itemId,
			"",
			Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_plank_variant")
		));
		return Optional.empty();
	}

	private static String logItemForPlank(String plankItemId) {
		int index = ActionGraphDomainKnowledge.plankItemIds().indexOf(plankItemId);
		if (index < 0 || index >= ActionGraphDomainKnowledge.logItemIds().size()) {
			return "";
		}
		return ActionGraphDomainKnowledge.logItemIds().get(index);
	}

	private boolean observedAnyWoodMaterial() {
		if (existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1)) > 0) {
			return true;
		}
		for (String logItemId : ActionGraphDomainKnowledge.logItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(logItemId, 1)) > 0) {
				return true;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1)) > 0) {
				return true;
			}
		}
		return false;
	}

	private Optional<ActionRoute> resolveMiningProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String itemId = goal.keys().getOrDefault("itemId", "");
		if (itemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(ActionRoute.empty());
		}
		List<String> blockIds = MinedBlockDropMapper.sourceBlockIdsForInventoryItem(itemId);
		if (blockIds.isEmpty()) {
			return Optional.empty();
		}
		String alternativeKey = "mining_provider:" + itemId;
		if (blockedAlternativeKeys.contains(alternativeKey)) {
			trace.add(event(
				"route_candidate_blocked",
				"mining_provider",
				itemId,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
			));
			return Optional.empty();
		}
		ArrayList<ActionPlanStep> steps = new ArrayList<>();
		int routeCost = 35;
		Optional<String> toolGoal = requiredMiningToolGoal(itemId);
		if (toolGoal.isPresent()) {
			Optional<ActionRoute> toolRoute = resolveGoal(
				ActionGoal.inventoryItem(toolGoal.get(), 1),
				depth + 1,
				resolving,
				trace
			);
			if (toolRoute.isEmpty()) {
				trace.add(event(
					"route_candidate_rejected",
					"mining_provider",
					itemId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "missing_tool_prerequisite", "toolItemId", toolGoal.get())
				));
				return Optional.empty();
			}
			steps.addAll(toolRoute.get().steps());
			routeCost += toolRoute.get().cost();
		}
		trace.add(event(
			"route_candidate_built",
			"mining_provider",
			itemId,
			"",
			Map.of("goal", goal.normalizedKey(), "cost", routeCost, "itemId", itemId)
		));
		LinkedHashMap<String, Object> args = new LinkedHashMap<>();
		args.put("itemId", itemId);
		args.put("blockIds", blockIds);
		args.put("quantity", deficitCount);
		args.put("targetCount", targetCount);
		ActionPlanStep step = new ActionPlanStep(ActionStepKind.PRIMITIVE, "mining_provider", itemId, "mine_block", "mine_block", args);
		steps.add(step);
		trace.add(event("primitive_planned", "mining_provider", itemId, "mine_block", Map.of(
			"primitive", "mine_block",
			"itemId", itemId,
			"blockIds", blockIds
		)));
		trace.add(event("route_selected", "mining_provider", itemId, "", Map.of("goal", goal.normalizedKey())));
		return Optional.of(new ActionRoute(List.copyOf(steps), routeCost));
	}

	private Optional<ActionRoute> resolveSmeltingProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(ActionRoute.empty());
		}

		Map<String, String> recipeQuery = new LinkedHashMap<>();
		recipeQuery.put("worldId", context.worldId());
		recipeQuery.put("actorId", context.actorId());
		ActionRoute bestRoute = null;
		String bestOptionId = "";
		int bestProvenanceRank = Integer.MAX_VALUE;
		for (ActionFact recipe : facts.query(ActionFactType.SMELT_RECIPE, recipeQuery).stream()
			.filter(this::usableFact)
			.filter(fact -> outputItemId.equals(scalar(fact.payload().get("outputItemId"), "")))
			.sorted(Comparator.comparing(fact -> fact.identity().keys().getOrDefault("optionId", "")))
			.toList()) {
			String optionId = recipe.identity().keys().getOrDefault("optionId", "");
			String alternativeKey = "smelting_provider:" + optionId;
			if (blockedAlternativeKeys.contains(alternativeKey)) {
				trace.add(event(
					"route_candidate_blocked",
					"smelting_provider",
					optionId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
				));
				continue;
			}
			String inputItemId = scalar(recipe.payload().get("inputItemId"), "");
			if (inputItemId.isBlank()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int inputQuantity = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			int maxInputQuantity = Math.max(1, intPayload(recipe, "maxInputQuantity", inputQuantity));
			if (inputQuantity > maxInputQuantity) {
				continue;
			}
			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 25;
			String stationItemId = scalar(recipe.payload().get("stationItemId"), "");
			int stationItemCount = intPayload(recipe, "stationItemCount", 0);
			if (!stationItemId.isBlank() && stationItemCount > 0) {
				Optional<ActionRoute> stationRoute = resolveGoal(
					ActionGoal.inventoryItem(stationItemId, stationItemCount),
					depth + 1,
					resolving,
					trace
				);
				if (stationRoute.isEmpty()) {
					continue;
				}
				steps.addAll(stationRoute.get().steps());
				routeCost += stationRoute.get().cost();
			}

			Optional<ActionRoute> inputRoute = resolveGoal(
				ActionGoal.inventoryItem(inputItemId, inputQuantity),
				depth + 1,
				resolving,
				trace
			);
			if (inputRoute.isEmpty()) {
				continue;
			}

			steps.addAll(inputRoute.get().steps());
			routeCost += inputRoute.get().cost();

			int cookTimeTicks = intPayload(recipe, "cookTimeTicks", DEFAULT_SMELT_COOK_TICKS);
			Optional<FuelPlan> fuelPlan = resolveSmeltingFuel(
				inputQuantity,
				cookTimeTicks,
				depth,
				resolving,
				trace,
				goal
			);
			if (fuelPlan.isPresent()) {
				steps.addAll(fuelPlan.get().route().steps());
				routeCost += fuelPlan.get().route().cost();
			}
			else {
				trace.add(event(
					"route_candidate_rejected",
					"smelting_provider",
					optionId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "missing_fuel_subgoal")
				));
				continue;
			}

			LinkedHashMap<String, Object> smeltArgs = new LinkedHashMap<>();
			smeltArgs.put("itemId", outputItemId);
			smeltArgs.put("inputItemId", inputItemId);
			smeltArgs.put("optionId", optionId);
			smeltArgs.put("quantity", deficitCount);
			smeltArgs.put("inputQuantity", inputQuantity);
			fuelPlan.ifPresent(plan -> {
				smeltArgs.put("fuelItemId", plan.itemId());
				smeltArgs.put("fuelQuantity", plan.quantity());
			});
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "smelt_item", "smelt_item", smeltArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "smelt_item", Map.of(
				"primitive", "smelt_item",
				"itemId", outputItemId,
				"inputItemId", inputItemId
			)));

			LinkedHashMap<String, Object> collectArgs = new LinkedHashMap<>();
			collectArgs.put("itemId", outputItemId);
			collectArgs.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "collect_smelted_item", "collect_smelted_item", collectArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "collect_smelted_item", Map.of(
				"primitive", "collect_smelted_item",
				"itemId", outputItemId
			)));
			trace.add(event(
				"route_candidate_built",
				"smelting_provider",
				optionId,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", routeCost, "outputItemId", outputItemId)
			));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			int provenanceRank = recipe.provenance().authoritative() ? 0 : 1;
			if (bestRoute == null
				|| candidateRoute.cost() < bestRoute.cost()
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank < bestProvenanceRank
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank == bestProvenanceRank && optionId.compareTo(bestOptionId) < 0) {
				bestRoute = candidateRoute;
				bestOptionId = optionId;
				bestProvenanceRank = provenanceRank;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		trace.add(event("route_selected", "smelting_provider", bestOptionId, "", Map.of("goal", goal.normalizedKey())));
		return Optional.of(bestRoute);
	}

	private Optional<FuelPlan> resolveSmeltingFuel(
		int inputQuantity,
		int cookTimeTicks,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace,
		ActionGoal smeltingGoal
	) {
		int requiredFuelTicks = inputQuantity * Math.max(1, cookTimeTicks);
		if (requiredFuelTicks <= 0) {
			return Optional.empty();
		}
		FuelPlan bestPlan = null;
		for (FuelCandidate candidate : fuelCandidates()) {
			int requiredQuantity = fuelItemsNeeded(requiredFuelTicks, candidate.fuelTicks());
			if (requiredQuantity <= 0) {
				continue;
			}
			ActionGoal fuelGoal = ActionGoal.inventoryItem(candidate.itemId(), requiredQuantity);
			int priority = fuelPriority(candidate.itemId());
			if (existingGoalCount(fuelGoal) >= requiredQuantity) {
				bestPlan = chooseCheaperFuelPlan(bestPlan, new FuelPlan(candidate.itemId(), requiredQuantity, ActionRoute.empty(), priority));
				continue;
			}
			Optional<ActionRoute> fuelRoute = resolveGoal(fuelGoal, depth + 1, resolving, trace);
			if (fuelRoute.isPresent()) {
				bestPlan = chooseCheaperFuelPlan(bestPlan, new FuelPlan(candidate.itemId(), requiredQuantity, fuelRoute.get(), priority));
			}
		}
		if (bestPlan == null) {
			return Optional.empty();
		}
		trace.add(event(
			bestPlan.route().steps().isEmpty() ? "fuel_subgoal_satisfied" : "fuel_subgoal_planned",
			"smelting_provider",
			bestPlan.itemId(),
			"",
			Map.of("goal", smeltingGoal.normalizedKey(), "fuelItemId", bestPlan.itemId(), "fuelQuantity", bestPlan.quantity())
		));
		return Optional.of(bestPlan);
	}

	private int fuelPriority(String itemId) {
		if ("minecraft:coal".equals(itemId) && hasExistingMiningToolFor("minecraft:coal")) {
			return 0;
		}
		if ("minecraft:coal".equals(itemId) || "minecraft:charcoal".equals(itemId)) {
			return 30;
		}
		if (ActionGraphDomainKnowledge.plankItemIds().contains(itemId) || "minecraft:stick".equals(itemId)) {
			return 10;
		}
		if (ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return 20;
		}
		return 5;
	}

	private boolean hasExistingMiningToolFor(String itemId) {
		for (String toolItemId : acceptableMiningTools(itemId)) {
			if (existingGoalCount(ActionGoal.inventoryItem(toolItemId, 1)) >= 1) {
				return true;
			}
		}
		return false;
	}

	private static FuelPlan chooseCheaperFuelPlan(FuelPlan current, FuelPlan candidate) {
		if (current == null) {
			return candidate;
		}
		int priorityCompare = Integer.compare(candidate.priority(), current.priority());
		if (priorityCompare < 0) {
			return candidate;
		}
		if (priorityCompare > 0) {
			return current;
		}
		int costCompare = Integer.compare(candidate.route().cost(), current.route().cost());
		if (costCompare < 0) {
			return candidate;
		}
		if (costCompare == 0 && candidate.quantity() < current.quantity()) {
			return candidate;
		}
		return current;
	}

	private Optional<ActionRoute> expandAlternative(
		ActionsetEntry entry,
		Map<String, Object> alternative,
		Map<String, Integer> params,
		List<ActionFact> matchedGuards,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		ArrayList<ActionPlanStep> steps = new ArrayList<>();
		int routeCost = cost(alternative);
		String alternativeId = scalar(alternative.get("id"), "<unnamed>");

		for (Object needObject : objectList(alternative.get("needs"))) {
			ActionGoal need = goalFromFactSpec(objectMap(needObject), params);
			Optional<ActionRoute> subRoute = resolveGoal(need, depth + 1, resolving, trace);
			if (subRoute.isEmpty()) {
				return Optional.empty();
			}
			steps.addAll(subRoute.get().steps());
			routeCost += subRoute.get().cost();
		}

		for (Object stepObject : objectList(alternative.get("steps"))) {
			Map<String, Object> step = objectMap(stepObject);
			String stepId = scalar(step.get("id"), "");
			if (step.containsKey("primitive")) {
				String primitive = scalar(step.get("primitive"), "");
				Map<String, Object> args = evaluateArgs(objectMap(step.get("args")), params);
				steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, entry.actionId(), alternativeId, stepId, primitive, args));
				trace.add(event("primitive_planned", entry.actionId(), alternativeId, stepId, Map.of("primitive", primitive)));
				continue;
			}
			if (step.containsKey("goal")) {
				ActionGoal stepGoal = goalFromFactSpec(objectMap(step.get("goal")), params);
				Optional<ActionRoute> subRoute = resolveGoal(stepGoal, depth + 1, resolving, trace);
				if (subRoute.isEmpty()) {
					return Optional.empty();
				}
				steps.addAll(subRoute.get().steps());
				routeCost += subRoute.get().cost();
				continue;
			}
			if (step.containsKey("actionset")) {
				String actionset = scalar(step.get("actionset"), "");
				Map<String, Object> args = evaluateArgs(objectMap(step.get("args")), params);
				steps.add(new ActionPlanStep(ActionStepKind.ACTIONSET, entry.actionId(), alternativeId, stepId, actionset, args));
				trace.add(event("step_planned", entry.actionId(), alternativeId, stepId, Map.of("actionset", actionset)));
				continue;
			}
			if (step.containsKey("watch")) {
				Map<String, Object> watch = evaluateArgs(objectMap(step.get("watch")), params);
				ActionWatchSpec watchSpec = watchSpec(watch, matchedGuards);
				steps.add(new ActionPlanStep(ActionStepKind.WATCH, entry.actionId(), alternativeId, stepId, "watch", watch, watchSpec));
				trace.add(event("watch_registered", entry.actionId(), alternativeId, stepId, watch));
			}
		}

		return Optional.of(new ActionRoute(steps, routeCost));
	}

	private List<ActionFact> matchedGuards(
		Map<String, Object> alternative,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		ArrayList<ActionFact> matched = new ArrayList<>();
		for (Object guardObject : objectList(alternative.get("guards"))) {
			Optional<ActionFact> fact = matchingFact(objectMap(guardObject), params, trace, actionId, alternativeId);
			if (fact.isEmpty()) {
				return null;
			}
			matched.add(fact.get());
		}
		return List.copyOf(matched);
	}

	private boolean goalSatisfied(ActionGoal goal, List<ActionTraceEvent> trace) {
		LinkedHashMap<String, Object> factSpec = new LinkedHashMap<>();
		factSpec.put("fact", goal.factType().id());
		factSpec.putAll(goal.keys());
		goal.minimums().forEach((key, value) -> factSpec.put(key, value));
		return matchingFact(factSpec, Map.of(), trace, "", "").isPresent();
	}

	private boolean factSatisfied(
		Map<String, Object> factSpec,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		return matchingFact(factSpec, params, trace, actionId, alternativeId).isPresent();
	}

	private Optional<ActionFact> matchingFact(
		Map<String, Object> factSpec,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		ActionFactCondition requirement = requirementFromSpec(factSpec, params);
		List<ActionFact> matches = facts.query(requirement.factType(), requirement.queryKeys());
		Optional<ActionFact> matched = matches.stream()
			.filter(this::usableFact)
			.filter(requirement::satisfiedBy)
			.sorted(Comparator
				.comparingLong(ActionFact::observedTick).reversed()
				.thenComparing(fact -> fact.identity().keys().toString()))
			.findFirst();
		trace.add(event(
			"fact_query",
			actionId,
			alternativeId,
			"",
			Map.of(
				"fact", requirement.factType().id(),
				"keys", requirement.queryKeys(),
				"satisfied", matched.isPresent(),
				"matchedIdentity", matched.map(fact -> fact.identity().keys()).orElse(Map.of())
			)
		));
		return matched;
	}

	private ActionWatchSpec watchSpec(Map<String, Object> watch, List<ActionFact> matchedGuards) {
		ActionFactCondition condition = requirementFromSpec(watch, Map.of());
		ActionFactCondition initialCondition = condition;
		Map<String, Object> progress = objectMap(watch.get("progress"));
		ActionWatchProgressKind progressKind = "area_ticking".equals(scalar(progress.get("kind"), ""))
			? ActionWatchProgressKind.AREA_TICKING
			: ActionWatchProgressKind.NONE;
		ActionFact sourceFact = null;
		if ("matched_fact".equals(scalar(progress.get("anchor"), ""))) {
			sourceFact = matchedGuards.stream()
				.filter(fact -> fact.identity().type() == initialCondition.factType())
				.filter(fact -> initialCondition.queryKeys().entrySet().stream()
					.allMatch(entry -> entry.getValue().equals(fact.identity().keys().get(entry.getKey()))))
				.findFirst()
				.orElse(null);
		}
		if (sourceFact != null) {
			LinkedHashMap<String, String> exactKeys = new LinkedHashMap<>(condition.queryKeys());
			exactKeys.putAll(sourceFact.identity().keys());
			condition = new ActionFactCondition(condition.factType(), exactKeys, condition.minimums());
		}
		return new ActionWatchSpec(
			condition,
			sourceFact == null ? null : sourceFact.identity(),
			longValue(watch.get("timeoutTicks"), 24000L),
			progressKind,
			anchorFromFact(sourceFact)
		);
	}

	private static ActionWatchAnchor anchorFromFact(ActionFact fact) {
		if (fact == null || !(fact.payload().get("origin") instanceof Map<?, ?> origin)) {
			return null;
		}
		Object x = origin.get("x");
		Object y = origin.get("y");
		Object z = origin.get("z");
		if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber) || !(z instanceof Number zNumber)) {
			return null;
		}
		return new ActionWatchAnchor(
			fact.identity().keys().getOrDefault("worldId", ""),
			fact.identity().keys().getOrDefault("dimension", ""),
			xNumber.intValue(),
			yNumber.intValue(),
			zNumber.intValue(),
			false
		);
	}

	private boolean usableFact(ActionFact fact) {
		return GUARD_USABLE_PROVENANCE.contains(fact.provenance()) && !fact.isStaleAt(context.currentTick());
	}

	private List<ActionsetEntry> matchingActionsets(ActionGoal goal) {
		return index.all().stream()
			.filter(entry -> producesGoal(entry.definition(), goal))
			.toList();
	}

	private static boolean producesGoal(Map<String, Object> action, ActionGoal goal) {
		for (Object produceObject : objectList(action.get("produces"))) {
			Map<String, Object> produced = objectMap(produceObject);
			if (!goal.factType().id().equals(scalar(produced.get("fact"), ""))) {
				continue;
			}
			boolean keysMatch = true;
			for (Map.Entry<String, String> key : goal.keys().entrySet()) {
				String producedValue = scalar(produced.get(key.getKey()), null);
				if (producedValue != null && !producedValue.equals(key.getValue())) {
					keysMatch = false;
					break;
				}
			}
			if (keysMatch) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Integer> bindParams(Map<String, Object> action, ActionGoal goal) {
		LinkedHashMap<String, Integer> params = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : objectMap(action.get("params")).entrySet()) {
			Map<String, Object> definition = objectMap(entry.getValue());
			Object defaultValue = definition.get("default");
			if (defaultValue instanceof Number number) {
				params.put(entry.getKey(), number.intValue());
			}
		}
		if (params.containsKey("quantity")) {
			params.put("quantity", goal.minimum("countAtLeast", goal.minimum("matureCountAtLeast", params.get("quantity"))));
		}
		int targetCount = goal.minimum("countAtLeast", goal.minimum("matureCountAtLeast", params.getOrDefault("quantity", 1)));
		int existingCount = existingGoalCount(goal);
		params.put("goal.targetCount", targetCount);
		params.put("goal.existingCount", existingCount);
		params.put("goal.deficitCount", Math.max(0, targetCount - existingCount));
		return Map.copyOf(params);
	}

	private int existingGoalCount(ActionGoal goal) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM && goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return 0;
		}
		LinkedHashMap<String, String> queryKeys = new LinkedHashMap<>();
		queryKeys.put("worldId", context.worldId());
		queryKeys.put("actorId", context.actorId());
		queryKeys.putAll(goal.keys());
		return facts.query(goal.factType(), queryKeys).stream()
			.filter(this::usableFact)
			.map(ActionFact::payload)
			.map(payload -> payload.get("count"))
			.filter(Number.class::isInstance)
			.map(Number.class::cast)
			.mapToInt(Number::intValue)
			.max()
			.orElse(0);
	}

	private int recipeInputDeficit(Map<String, Integer> inputCounts, int craftTimes) {
		int deficit = 0;
		for (Map.Entry<String, Integer> input : inputCounts.entrySet()) {
			int requiredCount = input.getValue() * craftTimes;
			if (requiredCount <= 0) {
				continue;
			}
			deficit += Math.max(0, requiredCount - existingGoalCount(ActionGoal.inventoryItem(input.getKey(), requiredCount)));
		}
		return deficit;
	}

	private Optional<String> requiredMiningToolGoal(String itemId) {
		List<String> acceptableTools = acceptableMiningTools(itemId);
		if (acceptableTools.isEmpty()) {
			return Optional.empty();
		}
		for (String toolItemId : acceptableTools) {
			if (existingGoalCount(ActionGoal.inventoryItem(toolItemId, 1)) >= 1) {
				return Optional.empty();
			}
		}
		return Optional.of(acceptableTools.getFirst());
	}

	private static List<String> acceptableMiningTools(String itemId) {
		return switch (itemId) {
			case "minecraft:cobblestone", "minecraft:coal" -> List.of(
				"minecraft:wooden_pickaxe",
				"minecraft:stone_pickaxe",
				"minecraft:iron_pickaxe",
				"minecraft:diamond_pickaxe",
				"minecraft:netherite_pickaxe",
				"minecraft:golden_pickaxe"
			);
			case "minecraft:raw_iron", "minecraft:raw_copper" -> List.of(
				"minecraft:stone_pickaxe",
				"minecraft:iron_pickaxe",
				"minecraft:diamond_pickaxe",
				"minecraft:netherite_pickaxe"
			);
			case "minecraft:raw_gold", "minecraft:diamond", "minecraft:emerald", "minecraft:redstone", "minecraft:lapis_lazuli" -> List.of(
				"minecraft:iron_pickaxe",
				"minecraft:diamond_pickaxe",
				"minecraft:netherite_pickaxe"
			);
			default -> List.of();
		};
	}

	private static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	private static List<FuelCandidate> fuelCandidates() {
		ArrayList<FuelCandidate> candidates = new ArrayList<>();
		for (String itemId : ActionGraphDomainKnowledge.plankItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_PLANKS));
		}
		candidates.add(new FuelCandidate("minecraft:stick", FUEL_TICKS_STICKS));
		for (String itemId : ActionGraphDomainKnowledge.logItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_LOGS));
		}
		candidates.add(new FuelCandidate("minecraft:coal", FUEL_TICKS_COAL));
		candidates.add(new FuelCandidate("minecraft:charcoal", FUEL_TICKS_COAL));
		return List.copyOf(candidates);
	}

	private ActionGoal goalFromFactSpec(Map<String, Object> factSpec, Map<String, Integer> params) {
		ActionFactType factType = ActionFactType.fromId(scalar(factSpec.get("fact"), ""))
			.orElseThrow(() -> new IllegalArgumentException("unknown fact type " + factSpec.get("fact")));
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		for (String key : identityKeyNames()) {
			String value = scalar(factSpec.get(key), null);
			if (value != null && !value.isBlank()) {
				keys.put(key, value);
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		if (factSpec.containsKey("countAtLeast")) {
			minimums.put("countAtLeast", evaluateInt(factSpec.get("countAtLeast"), params));
		}
		if (factSpec.containsKey("matureCountAtLeast")) {
			minimums.put("matureCountAtLeast", evaluateInt(factSpec.get("matureCountAtLeast"), params));
		}
		return new ActionGoal(factType, keys, minimums);
	}

	private ActionFactCondition requirementFromSpec(Map<String, Object> factSpec, Map<String, Integer> params) {
		ActionFactType factType = ActionFactType.fromId(scalar(factSpec.get("fact"), ""))
			.orElseThrow(() -> new IllegalArgumentException("unknown fact type " + factSpec.get("fact")));
		LinkedHashMap<String, String> queryKeys = new LinkedHashMap<>();
		queryKeys.put("worldId", context.worldId());
		if (factType == ActionFactType.INVENTORY_ITEM || factType == ActionFactType.INVENTORY_RESOURCE || factType == ActionFactType.INVENTORY_TOOL || factType == ActionFactType.CRAFT_RECIPE || factType == ActionFactType.SMELT_RECIPE) {
			queryKeys.put("actorId", context.actorId());
		}
		if (worldDimensionScopedFact(factType)) {
			queryKeys.put("dimension", context.dimension());
		}
		for (String key : identityKeyNames()) {
			String value = scalar(factSpec.get(key), null);
			if (value != null && !value.isBlank()) {
				queryKeys.put(key, value);
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		if (factSpec.containsKey("countAtLeast")) {
			minimums.put("count", evaluateInt(factSpec.get("countAtLeast"), params));
		}
		if (factSpec.containsKey("matureCountAtLeast")) {
			minimums.put("matureCount", evaluateInt(factSpec.get("matureCountAtLeast"), params));
		}
		return new ActionFactCondition(factType, queryKeys, minimums);
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static Map<String, Integer> recipeInputCounts(ActionFact recipe) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		Object inputCounts = recipe.payload().get("inputCounts");
		if (inputCounts instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String itemId = String.valueOf(entry.getKey());
				if (itemId.isBlank()) {
					continue;
				}
				int count = entry.getValue() instanceof Number number ? number.intValue() : 0;
				if (count > 0) {
					counts.merge(itemId, count, Integer::sum);
				}
			}
		}
		if (!counts.isEmpty()) {
			return Map.copyOf(counts);
		}
		Object inputItemIds = recipe.payload().get("inputItemIds");
		if (inputItemIds instanceof List<?> list) {
			for (Object item : list) {
				String itemId = String.valueOf(item);
				if (!itemId.isBlank()) {
					counts.merge(itemId, 1, Integer::sum);
				}
			}
		}
		return Map.copyOf(counts);
	}

	private static int intPayload(ActionFact fact, String key, int fallback) {
		Object value = fact.payload().get(key);
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static List<Map<String, Object>> alternatives(ActionsetEntry entry) {
		return objectList(entry.definition().get("alternatives")).stream()
			.map(ActionResolver::objectMap)
			.sorted(Comparator.comparingInt(ActionResolver::cost))
			.toList();
	}

	private static int cost(Map<String, Object> alternative) {
		Object cost = alternative.get("cost");
		return cost instanceof Number number ? number.intValue() : 10;
	}

	private static Map<String, Object> evaluateArgs(Map<String, Object> args, Map<String, Integer> params) {
		LinkedHashMap<String, Object> evaluated = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : args.entrySet()) {
			evaluated.put(entry.getKey(), evaluateValue(entry.getValue(), params));
		}
		return evaluated;
	}

	private static Object evaluateValue(Object value, Map<String, Integer> params) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> typed = objectMap(map);
			if (typed.containsKey("expr")) {
				return evaluateInt(typed, params);
			}
			return evaluateArgs(typed, params);
		}
		if (value instanceof List<?> list) {
			return list.stream()
				.map(item -> evaluateValue(item, params))
				.toList();
		}
		return value;
	}

	private static int evaluateInt(Object value, Map<String, Integer> params) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof Map<?, ?> map) {
			String expression = scalar(objectMap(map).get("expr"), "");
			return new IntegerExpression(expression, params).parse();
		}
		return new IntegerExpression(scalar(value, "0"), params).parse();
	}

	private static ActionTraceEvent event(
		String eventType,
		String actionId,
		String alternativeId,
		String stepId,
		Map<String, Object> payload
	) {
		return new ActionTraceEvent(eventType, actionId, alternativeId, stepId, payload);
	}

	private static List<String> identityKeyNames() {
		return List.of("itemId", "resourceKind", "toolTag", "dimension", "blockPos", "cropId", "siteId", "siteType", "plotId", "candidateId", "sourceId", "sampleId", "entityTypeId", "entityId", "recipeId", "optionId", "watchId", "goalId");
	}

	private static boolean worldDimensionScopedFact(ActionFactType factType) {
		return factType == ActionFactType.WORLD_BLOCK
			|| factType == ActionFactType.WORLD_CROP
			|| factType == ActionFactType.WORLD_CROP_GROUP
			|| factType == ActionFactType.WORLD_SITE
			|| factType == ActionFactType.WORLD_FARM_SITE
			|| factType == ActionFactType.WORLD_FARM_PLOT
			|| factType == ActionFactType.WORLD_SOIL_CANDIDATE
			|| factType == ActionFactType.WORLD_HYDRATION_SOURCE
			|| factType == ActionFactType.WORLD_LIGHT_LEVEL
			|| factType == ActionFactType.WORLD_CROP_SEED_SOURCE
			|| factType == ActionFactType.WORLD_ENTITY;
	}

	private static Map<String, Object> objectMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return List.copyOf(list);
		}
		return List.of();
	}

	private static String scalar(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}

	private record FuelCandidate(String itemId, int fuelTicks) {
	}

	private record FuelPlan(String itemId, int quantity, ActionRoute route, int priority) {
	}

	private static final class IntegerExpression {
		private final String expression;
		private final Map<String, Integer> params;
		private int index;

		IntegerExpression(String expression, Map<String, Integer> params) {
			this.expression = expression == null ? "" : expression;
			this.params = params == null ? Map.of() : params;
		}

		int parse() {
			int value = parseExpression();
			skipWhitespace();
			if (index != expression.length()) {
				throw new IllegalArgumentException("unexpected token in expression: " + expression);
			}
			return value;
		}

		private int parseExpression() {
			int value = parseTerm();
			while (true) {
				skipWhitespace();
				if (match('+')) {
					value += parseTerm();
				}
				else if (match('-')) {
					value -= parseTerm();
				}
				else {
					return value;
				}
			}
		}

		private int parseTerm() {
			int value = parseFactor();
			while (true) {
				skipWhitespace();
				if (match('*')) {
					value *= parseFactor();
				}
				else if (match('/')) {
					value /= parseFactor();
				}
				else {
					return value;
				}
			}
		}

		private int parseFactor() {
			skipWhitespace();
			if (match('-')) {
				return -parseFactor();
			}
			if (match('(')) {
				int value = parseExpression();
				expect(')');
				return value;
			}
			if (peekDigit()) {
				return parseInteger();
			}
			return parseIdentifier();
		}

		private int parseInteger() {
			int start = index;
			while (index < expression.length() && Character.isDigit(expression.charAt(index))) {
				index++;
			}
			return Integer.parseInt(expression.substring(start, index));
		}

		private int parseIdentifier() {
			int start = index;
			while (index < expression.length()) {
				char value = expression.charAt(index);
				if (!Character.isLetterOrDigit(value) && value != '_' && value != '.') {
					break;
				}
				index++;
			}
			String identifier = expression.substring(start, index);
			Integer exact = params.get(identifier);
			if (exact != null) {
				return exact;
			}
			if (identifier.startsWith("params.")) {
				String param = identifier.substring("params.".length());
				Integer value = params.get(param);
				if (value != null) {
					return value;
				}
			}
			throw new IllegalArgumentException("unknown identifier in expression: " + identifier);
		}

		private boolean match(char expected) {
			if (index < expression.length() && expression.charAt(index) == expected) {
				index++;
				return true;
			}
			return false;
		}

		private void expect(char expected) {
			if (!match(expected)) {
				throw new IllegalArgumentException("expected '" + expected + "' in expression: " + expression);
			}
		}

		private boolean peekDigit() {
			return index < expression.length() && Character.isDigit(expression.charAt(index));
		}

		private void skipWhitespace() {
			while (index < expression.length() && Character.isWhitespace(expression.charAt(index))) {
				index++;
			}
		}
	}
}
