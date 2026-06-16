package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.MinedBlockDropMapper;

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
			Optional<ActionRoute> resourceRoute = resolveResourceProviderGoal(goal, trace);
			if (resourceRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return resourceRoute;
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
			Optional<ActionRoute> resourceRoute = resolveResourceProviderGoal(goal, trace);
			if (resourceRoute.isPresent()) {
				resolving.remove(goal.normalizedKey());
				return resourceRoute;
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

				if (!guardsSatisfied(alternative, params, trace, entry.actionId(), alternativeId)) {
					continue;
				}

				Optional<ActionRoute> expanded = expandAlternative(entry, alternative, params, depth, resolving, trace);
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
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return Optional.empty();
		}
		String resourceKind = goal.keys().getOrDefault("resourceKind", "");
		if (!"WOOD_LOGS".equals(resourceKind)) {
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
			trace.add(event(
				"route_candidate_built",
				"recipe_provider",
				recipeId,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", 15, "outputItemId", outputItemId)
			));

			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 15;
			boolean inputsResolved = true;
			for (Map.Entry<String, Integer> input : inputCounts.entrySet()) {
				int requiredCount = input.getValue() * craftTimes;
				if (requiredCount <= 0) {
					continue;
				}
				Optional<ActionRoute> subRoute = resolveGoal(
					ActionGoal.inventoryItem(input.getKey(), requiredCount),
					depth + 1,
					resolving,
					trace
				);
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
			trace.add(event("route_selected", "recipe_provider", recipeId, "", Map.of("goal", goal.normalizedKey())));
			return Optional.of(new ActionRoute(steps, routeCost));
		}

		return Optional.empty();
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
			trace.add(event(
				"route_candidate_built",
				"smelting_provider",
				optionId,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", 25, "outputItemId", outputItemId)
			));

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
			LinkedHashMap<String, Object> smeltArgs = new LinkedHashMap<>();
			smeltArgs.put("itemId", outputItemId);
			smeltArgs.put("inputItemId", inputItemId);
			smeltArgs.put("optionId", optionId);
			smeltArgs.put("quantity", deficitCount);
			smeltArgs.put("inputQuantity", inputQuantity);
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
			trace.add(event("route_selected", "smelting_provider", optionId, "", Map.of("goal", goal.normalizedKey())));
			return Optional.of(new ActionRoute(steps, routeCost));
		}

		return Optional.empty();
	}

	private Optional<ActionRoute> expandAlternative(
		ActionsetEntry entry,
		Map<String, Object> alternative,
		Map<String, Integer> params,
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
				steps.add(new ActionPlanStep(ActionStepKind.WATCH, entry.actionId(), alternativeId, stepId, "watch", watch));
				trace.add(event("watch_registered", entry.actionId(), alternativeId, stepId, watch));
			}
		}

		return Optional.of(new ActionRoute(steps, routeCost));
	}

	private boolean guardsSatisfied(
		Map<String, Object> alternative,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		for (Object guardObject : objectList(alternative.get("guards"))) {
			if (!factSatisfied(objectMap(guardObject), params, trace, actionId, alternativeId)) {
				return false;
			}
		}
		return true;
	}

	private boolean goalSatisfied(ActionGoal goal, List<ActionTraceEvent> trace) {
		LinkedHashMap<String, Object> factSpec = new LinkedHashMap<>();
		factSpec.put("fact", goal.factType().id());
		factSpec.putAll(goal.keys());
		goal.minimums().forEach((key, value) -> factSpec.put(key, value));
		return factSatisfied(factSpec, Map.of(), trace, "", "");
	}

	private boolean factSatisfied(
		Map<String, Object> factSpec,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		FactRequirement requirement = requirementFromSpec(factSpec, params);
		List<ActionFact> matches = facts.query(requirement.factType(), requirement.queryKeys());
		boolean satisfied = matches.stream()
			.filter(this::usableFact)
			.anyMatch(requirement::satisfiedBy);
		trace.add(event(
			"fact_query",
			actionId,
			alternativeId,
			"",
			Map.of(
				"fact", requirement.factType().id(),
				"keys", requirement.queryKeys(),
				"satisfied", satisfied
			)
		));
		return satisfied;
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

	private FactRequirement requirementFromSpec(Map<String, Object> factSpec, Map<String, Integer> params) {
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
		return new FactRequirement(factType, queryKeys, minimums);
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

	private record FactRequirement(
		ActionFactType factType,
		Map<String, String> queryKeys,
		Map<String, Integer> minimums
	) {
		boolean satisfiedBy(ActionFact fact) {
			for (Map.Entry<String, Integer> minimum : minimums.entrySet()) {
				Object value = fact.payload().get(minimum.getKey());
				if (!(value instanceof Number number) || number.intValue() < minimum.getValue()) {
					return false;
				}
			}
			return true;
		}
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
