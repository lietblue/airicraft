package ai.moeru.airicraft.agent.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure compiler from captured loot-table values to executable block acquisition knowledge.
 */
public final class BlockLootTableCompiler {
	private BlockLootTableCompiler() {
	}

	public static BlockAcquisitionIndex compile(
		List<BlockLootTableSource> sources,
		Map<String, List<String>> itemTags
	) {
		if (sources == null || sources.isEmpty()) {
			return BlockAcquisitionIndex.empty();
		}
		Map<String, List<String>> normalizedTags = normalizedTags(itemTags);
		LinkedHashMap<String, MutableRule> compiled = new LinkedHashMap<>();
		for (BlockLootTableSource source : sources.stream()
			.filter(java.util.Objects::nonNull)
			.sorted(Comparator.comparing(BlockLootTableSource::blockId))
			.toList()) {
			JsonElement parsed = JsonParser.parseString(source.lootTableJson());
			if (!parsed.isJsonObject()) {
				continue;
			}
			JsonObject table = parsed.getAsJsonObject();
			JsonArray pools = array(table, "pools");
			for (JsonElement poolElement : pools) {
				if (!poolElement.isJsonObject()) {
					continue;
				}
				JsonObject pool = poolElement.getAsJsonObject();
				List<JsonObject> inheritedConditions = conditions(pool);
				List<JsonObject> inheritedFunctions = functions(pool);
				RollEstimate rolls = rollEstimate(pool.get("rolls"));
				for (JsonElement entryElement : array(pool, "entries")) {
					if (entryElement.isJsonObject()) {
						compileEntry(
							source,
							entryElement.getAsJsonObject(),
							inheritedConditions,
							inheritedFunctions,
							rolls,
							normalizedTags,
							compiled
						);
					}
				}
			}
		}
		return BlockAcquisitionIndex.of(compiled.values().stream()
			.map(MutableRule::toRule)
			.toList());
	}

	private static void compileEntry(
		BlockLootTableSource source,
		JsonObject entry,
		List<JsonObject> inheritedConditions,
		List<JsonObject> inheritedFunctions,
		RollEstimate rolls,
		Map<String, List<String>> itemTags,
		Map<String, MutableRule> compiled
	) {
		ArrayList<JsonObject> allConditions = new ArrayList<>(inheritedConditions);
		allConditions.addAll(conditions(entry));
		ArrayList<JsonObject> allFunctions = new ArrayList<>(inheritedFunctions);
		allFunctions.addAll(functions(entry));
		String type = string(entry, "type");
		if ("minecraft:item".equals(type)) {
			String outputItemId = string(entry, "name");
			if (!outputItemId.isBlank()) {
				compileOutput(source, outputItemId, allConditions, allFunctions, rolls, itemTags, compiled);
			}
		}
		else if ("minecraft:tag".equals(type)) {
			String tagId = withoutTagPrefix(string(entry, "name"));
			for (String outputItemId : itemTags.getOrDefault(tagId, List.of())) {
				compileOutput(source, outputItemId, allConditions, allFunctions, rolls, itemTags, compiled);
			}
		}
		for (String childField : List.of("children", "entries")) {
			for (JsonElement child : array(entry, childField)) {
				if (child.isJsonObject()) {
					compileEntry(source, child.getAsJsonObject(), allConditions, allFunctions, rolls, itemTags, compiled);
				}
			}
		}
	}

	private static void compileOutput(
		BlockLootTableSource source,
		String outputItemId,
		List<JsonObject> conditions,
		List<JsonObject> functions,
		RollEstimate rolls,
		Map<String, List<String>> itemTags,
		Map<String, MutableRule> compiled
	) {
		ToolConstraint toolConstraint = analyzeAll(conditions, itemTags);
		if (toolConstraint.unsupported()) {
			return;
		}
		List<String> tools;
		boolean emptyHandAllowed;
		if (toolConstraint.constrained()) {
			LinkedHashSet<String> exactTools = new LinkedHashSet<>(toolConstraint.itemIds());
			if (source.correctToolRequired()) {
				exactTools.retainAll(source.suitableToolItemIds());
			}
			tools = exactTools.stream().sorted().toList();
			emptyHandAllowed = false;
		}
		else if (source.correctToolRequired()) {
			tools = source.suitableToolItemIds();
			emptyHandAllowed = false;
		}
		else {
			boolean hasExcludedTools = conditions.stream().anyMatch(BlockLootTableCompiler::containsInvertedToolConstraint);
			tools = hasExcludedTools ? List.of() : source.breakTicksByToolItemId().entrySet().stream()
				.filter(entry -> entry.getValue() < source.emptyHandBreakTicks())
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
			emptyHandAllowed = true;
		}
		if (!emptyHandAllowed && tools.isEmpty()) {
			return;
		}
		DropEstimate dropEstimate = dropEstimate(conditions, functions, rolls);
		if (dropEstimate.known() && dropEstimate.expectedDropsPerBreak() <= 0.0) {
			return;
		}
		boolean probabilistic = dropEstimate.known()
			? dropEstimate.dropProbability() < 1.0
			: conditions.stream().anyMatch(BlockLootTableCompiler::containsStochasticCondition);
		Map<String, Integer> toolBreakTicks = breakTicksForTools(source, tools);
		String key = source.blockId() + "\u0000" + outputItemId;
		MutableRule existing = compiled.get(key);
		if (existing == null) {
			compiled.put(key, new MutableRule(
				source.blockId(),
				outputItemId,
				new LinkedHashSet<>(tools),
				emptyHandAllowed,
				probabilistic,
				source.lootTableId(),
				dropEstimate,
				source.emptyHandBreakTicks(),
				toolBreakTicks
			));
			return;
		}
		existing.tools.addAll(tools);
		existing.emptyHandAllowed |= emptyHandAllowed;
		existing.probabilistic |= probabilistic;
		existing.mergeEstimate(dropEstimate);
		existing.emptyHandBreakTicks = Math.min(existing.emptyHandBreakTicks, source.emptyHandBreakTicks());
		toolBreakTicks.forEach((toolId, ticks) -> existing.breakTicksByToolItemId.merge(toolId, ticks, Math::min));
	}

	private static Map<String, Integer> breakTicksForTools(BlockLootTableSource source, List<String> tools) {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (String tool : tools) {
			result.put(tool, source.breakTicksByToolItemId().getOrDefault(tool, source.emptyHandBreakTicks()));
		}
		return Collections.unmodifiableMap(result);
	}

	private static DropEstimate dropEstimate(
		List<JsonObject> conditions,
		List<JsonObject> functions,
		RollEstimate rolls
	) {
		ProbabilityEstimate probability = probabilityOfAll(conditions);
		CountEstimate count = countAfterFunctions(functions);
		boolean known = probability.known() && count.known() && rolls.known();
		double expectedDrops = probability.probability() * count.expectedCount() * rolls.expectedRolls();
		double dropProbability;
		if (rolls.exactRolls() >= 0) {
			dropProbability = 1.0 - Math.pow(1.0 - probability.probability(), rolls.exactRolls());
		}
		else {
			dropProbability = probability.probability();
		}
		if (!Double.isFinite(dropProbability) || dropProbability <= 0.0) {
			dropProbability = known ? 0.0 : 1.0;
		}
		if (!Double.isFinite(expectedDrops) || expectedDrops <= 0.0) {
			expectedDrops = known ? 0.0 : 1.0;
		}
		return new DropEstimate(known, dropProbability, expectedDrops);
	}

	private static ProbabilityEstimate probabilityOfAll(List<JsonObject> conditions) {
		ProbabilityEstimate result = ProbabilityEstimate.deterministic();
		for (JsonObject condition : conditions) {
			ProbabilityEstimate next = probabilityOf(condition);
			if (next.relevant()) {
				result = result.and(next);
			}
		}
		return result;
	}

	private static ProbabilityEstimate probabilityOf(JsonObject condition) {
		String type = string(condition, "condition");
		if ("minecraft:random_chance".equals(type)) {
			return ProbabilityEstimate.random(number(condition.get("chance")));
		}
		if ("minecraft:table_bonus".equals(type) || "minecraft:bonus_level_table_condition".equals(type)) {
			JsonArray chances = array(condition, "chances");
			return chances.isEmpty()
				? ProbabilityEstimate.unknownRandom()
				: ProbabilityEstimate.random(number(chances.get(0)));
		}
		if ("minecraft:random_chance_with_enchanted_bonus".equals(type)) {
			double unenchantedChance = number(condition.get("unenchanted_chance"));
			if (!Double.isFinite(unenchantedChance)) {
				unenchantedChance = number(condition.get("chance"));
			}
			return ProbabilityEstimate.random(unenchantedChance);
		}
		if ("minecraft:all_of".equals(type)) {
			ProbabilityEstimate result = ProbabilityEstimate.deterministic();
			for (JsonElement term : array(condition, "terms")) {
				if (term.isJsonObject()) {
					ProbabilityEstimate next = probabilityOf(term.getAsJsonObject());
					if (next.relevant()) {
						result = result.and(next);
					}
				}
			}
			return result;
		}
		if ("minecraft:any_of".equals(type) || "minecraft:alternative".equals(type)) {
			ProbabilityEstimate result = ProbabilityEstimate.noneRelevant();
			for (JsonElement term : array(condition, "terms")) {
				if (term.isJsonObject()) {
					ProbabilityEstimate next = probabilityOf(term.getAsJsonObject());
					if (next.relevant()) {
						result = result.or(next);
					}
				}
			}
			return result;
		}
		if ("minecraft:inverted".equals(type)) {
			JsonObject term = object(condition, "term");
			ProbabilityEstimate nested = term.entrySet().isEmpty()
				? ProbabilityEstimate.noneRelevant()
				: probabilityOf(term);
			return nested.relevant() ? nested.inverted() : nested;
		}
		return type.contains("random")
			? ProbabilityEstimate.unknownRandom()
			: ProbabilityEstimate.noneRelevant();
	}

	private static CountEstimate countAfterFunctions(List<JsonObject> functions) {
		CountEstimate result = CountEstimate.one();
		for (JsonObject function : functions) {
			String type = string(function, "function");
			if ("minecraft:set_count".equals(type)) {
				NumberEstimate count = numberProvider(function.get("count"));
				if (!conditions(function).isEmpty() || !count.known()) {
					result = result.unknown();
					continue;
				}
				result = booleanValue(function, "add")
					? result.add(count.value())
					: result.replace(count.value());
			}
			else if ("minecraft:limit_count".equals(type)) {
				result = result.unknown();
			}
			// Enchantment and explosion count functions are neutral for the baseline
			// unenchanted, non-explosion mining context captured by this index.
		}
		return result;
	}

	private static NumberEstimate numberProvider(JsonElement value) {
		double primitive = number(value);
		if (Double.isFinite(primitive)) {
			return NumberEstimate.known(primitive);
		}
		if (value == null || !value.isJsonObject()) {
			return NumberEstimate.unknown();
		}
		JsonObject provider = value.getAsJsonObject();
		String type = string(provider, "type");
		if ("minecraft:constant".equals(type)) {
			double constant = number(provider.get("value"));
			return Double.isFinite(constant) ? NumberEstimate.known(constant) : NumberEstimate.unknown();
		}
		if (type.isEmpty() || "minecraft:uniform".equals(type)) {
			double minimum = number(provider.get("min"));
			double maximum = number(provider.get("max"));
			return Double.isFinite(minimum) && Double.isFinite(maximum) && maximum >= minimum
				? NumberEstimate.known((minimum + maximum) / 2.0)
				: NumberEstimate.unknown();
		}
		return NumberEstimate.unknown();
	}

	private static RollEstimate rollEstimate(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return RollEstimate.one();
		}
		NumberEstimate estimate = numberProvider(value);
		if (!estimate.known() || estimate.value() < 0.0) {
			return RollEstimate.unknown();
		}
		int exactRolls = estimate.value() == Math.rint(estimate.value())
			? (int) Math.min(Integer.MAX_VALUE, estimate.value())
			: -1;
		return new RollEstimate(exactRolls >= 0, estimate.value(), exactRolls);
	}

	private static ToolConstraint analyzeAll(List<JsonObject> conditions, Map<String, List<String>> itemTags) {
		ToolConstraint combined = ToolConstraint.none();
		for (JsonObject condition : conditions) {
			combined = and(combined, analyze(condition, itemTags));
			if (combined.unsupported()) {
				return combined;
			}
		}
		return combined;
	}

	private static boolean containsInvertedToolConstraint(JsonObject condition) {
		String type = string(condition, "condition");
		if ("minecraft:inverted".equals(type)) {
			return containsToolConstraint(object(condition, "term"));
		}
		for (JsonElement term : array(condition, "terms")) {
			if (term.isJsonObject() && containsInvertedToolConstraint(term.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsToolConstraint(JsonObject condition) {
		if ("minecraft:match_tool".equals(string(condition, "condition"))) {
			return true;
		}
		for (JsonElement term : array(condition, "terms")) {
			if (term.isJsonObject() && containsToolConstraint(term.getAsJsonObject())) {
				return true;
			}
		}
		JsonObject nested = object(condition, "term");
		return !nested.entrySet().isEmpty() && containsToolConstraint(nested);
	}

	private static ToolConstraint analyze(JsonObject condition, Map<String, List<String>> itemTags) {
		String type = string(condition, "condition");
		if ("minecraft:match_tool".equals(type)) {
			JsonObject predicate = object(condition, "predicate");
			Set<String> itemIds = itemIds(predicate.get("items"), itemTags);
			boolean hasOtherPredicates = predicate.has("predicates") || predicate.has("components") || predicate.has("count");
			if (itemIds.isEmpty() || hasOtherPredicates) {
				return ToolConstraint.unsupportedConstraint();
			}
			return ToolConstraint.exact(itemIds);
		}
		if ("minecraft:any_of".equals(type) || "minecraft:alternative".equals(type)) {
			LinkedHashSet<String> supportedItems = new LinkedHashSet<>();
			boolean sawToolConstraint = false;
			for (JsonElement term : array(condition, "terms")) {
				if (!term.isJsonObject()) {
					continue;
				}
				ToolConstraint analyzed = analyze(term.getAsJsonObject(), itemTags);
				if (analyzed.constrained()) {
					sawToolConstraint = true;
					if (!analyzed.unsupported()) {
						supportedItems.addAll(analyzed.itemIds());
					}
				}
			}
			if (!sawToolConstraint) {
				return ToolConstraint.none();
			}
			return supportedItems.isEmpty()
				? ToolConstraint.unsupportedConstraint()
				: ToolConstraint.exact(supportedItems);
		}
		if ("minecraft:all_of".equals(type)) {
			ToolConstraint combined = ToolConstraint.none();
			for (JsonElement term : array(condition, "terms")) {
				if (term.isJsonObject()) {
					combined = and(combined, analyze(term.getAsJsonObject(), itemTags));
				}
			}
			return combined;
		}
		if ("minecraft:inverted".equals(type)) {
			return ToolConstraint.none();
		}
		return ToolConstraint.none();
	}

	private static ToolConstraint and(ToolConstraint left, ToolConstraint right) {
		if (left.unsupported() || right.unsupported()) {
			return ToolConstraint.unsupportedConstraint();
		}
		if (!left.constrained()) {
			return right;
		}
		if (!right.constrained()) {
			return left;
		}
		LinkedHashSet<String> intersection = new LinkedHashSet<>(left.itemIds());
		intersection.retainAll(right.itemIds());
		return intersection.isEmpty()
			? ToolConstraint.unsupportedConstraint()
			: ToolConstraint.exact(intersection);
	}

	private static Set<String> itemIds(JsonElement value, Map<String, List<String>> itemTags) {
		if (value == null || value.isJsonNull()) {
			return Set.of();
		}
		LinkedHashSet<String> result = new LinkedHashSet<>();
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			addItemOrTag(value.getAsString(), itemTags, result);
		}
		else if (value.isJsonArray()) {
			for (JsonElement element : value.getAsJsonArray()) {
				if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
					addItemOrTag(element.getAsString(), itemTags, result);
				}
			}
		}
		return Collections.unmodifiableSet(result);
	}

	private static void addItemOrTag(String value, Map<String, List<String>> itemTags, Collection<String> output) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			return;
		}
		if (normalized.startsWith("#")) {
			output.addAll(itemTags.getOrDefault(withoutTagPrefix(normalized), List.of()));
		}
		else {
			output.add(normalized);
		}
	}

	private static boolean containsStochasticCondition(JsonObject condition) {
		String type = string(condition, "condition");
		if ("minecraft:random_chance".equals(type)
			|| "minecraft:random_chance_with_enchanted_bonus".equals(type)
			|| "minecraft:table_bonus".equals(type)
			|| "minecraft:bonus_level_table_condition".equals(type)
			|| type.contains("random")) {
			return true;
		}
		for (String field : List.of("terms")) {
			for (JsonElement child : array(condition, field)) {
				if (child.isJsonObject() && containsStochasticCondition(child.getAsJsonObject())) {
					return true;
				}
			}
		}
		JsonObject term = object(condition, "term");
		return !term.entrySet().isEmpty() && containsStochasticCondition(term);
	}

	private static List<JsonObject> conditions(JsonObject object) {
		ArrayList<JsonObject> result = new ArrayList<>();
		for (JsonElement condition : array(object, "conditions")) {
			if (condition.isJsonObject()) {
				result.add(condition.getAsJsonObject());
			}
		}
		return List.copyOf(result);
	}

	private static List<JsonObject> functions(JsonObject object) {
		ArrayList<JsonObject> result = new ArrayList<>();
		for (JsonElement function : array(object, "functions")) {
			if (function.isJsonObject()) {
				result.add(function.getAsJsonObject());
			}
		}
		return List.copyOf(result);
	}

	private static JsonArray array(JsonObject object, String field) {
		JsonElement value = object == null ? null : object.get(field);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
	}

	private static JsonObject object(JsonObject object, String field) {
		JsonElement value = object == null ? null : object.get(field);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = object == null ? null : object.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? value.getAsString().trim()
			: "";
	}

	private static double number(JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return Double.NaN;
		}
		try {
			return value.getAsDouble();
		}
		catch (NumberFormatException ignored) {
			return Double.NaN;
		}
	}

	private static boolean booleanValue(JsonObject object, String field) {
		JsonElement value = object == null ? null : object.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			&& value.getAsBoolean();
	}

	private static String withoutTagPrefix(String value) {
		String normalized = value == null ? "" : value.trim();
		return normalized.startsWith("#") ? normalized.substring(1) : normalized;
	}

	private static Map<String, List<String>> normalizedTags(Map<String, List<String>> itemTags) {
		if (itemTags == null || itemTags.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
		itemTags.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> normalized.put(
				withoutTagPrefix(entry.getKey()),
				entry.getValue() == null ? List.of() : entry.getValue().stream()
					.filter(value -> value != null && !value.isBlank())
					.map(String::trim)
					.distinct()
					.sorted()
					.toList()
			));
		return Collections.unmodifiableMap(normalized);
	}

	private record ToolConstraint(boolean constrained, boolean unsupported, Set<String> itemIds) {
		private ToolConstraint {
			itemIds = itemIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(itemIds));
		}

		private static ToolConstraint none() {
			return new ToolConstraint(false, false, Set.of());
		}

		private static ToolConstraint exact(Collection<String> itemIds) {
			return new ToolConstraint(true, false, new LinkedHashSet<>(itemIds));
		}

		private static ToolConstraint unsupportedConstraint() {
			return new ToolConstraint(true, true, Set.of());
		}
	}

	private record ProbabilityEstimate(boolean known, boolean relevant, double probability) {
		private static ProbabilityEstimate deterministic() {
			return new ProbabilityEstimate(true, true, 1.0);
		}

		private static ProbabilityEstimate noneRelevant() {
			return new ProbabilityEstimate(true, false, 0.0);
		}

		private static ProbabilityEstimate random(double probability) {
			return Double.isFinite(probability) && probability >= 0.0 && probability <= 1.0
				? new ProbabilityEstimate(true, true, probability)
				: unknownRandom();
		}

		private static ProbabilityEstimate unknownRandom() {
			return new ProbabilityEstimate(false, true, 1.0);
		}

		private ProbabilityEstimate and(ProbabilityEstimate other) {
			return new ProbabilityEstimate(
				known && other.known,
				true,
				probability * other.probability
			);
		}

		private ProbabilityEstimate or(ProbabilityEstimate other) {
			if (!relevant) {
				return other;
			}
			return new ProbabilityEstimate(
				known && other.known,
				true,
				1.0 - ((1.0 - probability) * (1.0 - other.probability))
			);
		}

		private ProbabilityEstimate inverted() {
			return new ProbabilityEstimate(known, true, 1.0 - probability);
		}
	}

	private record CountEstimate(boolean known, double expectedCount) {
		private static CountEstimate one() {
			return new CountEstimate(true, 1.0);
		}

		private CountEstimate unknown() {
			return new CountEstimate(false, expectedCount);
		}

		private CountEstimate replace(double value) {
			return new CountEstimate(known, value);
		}

		private CountEstimate add(double value) {
			return new CountEstimate(known, expectedCount + value);
		}
	}

	private record NumberEstimate(boolean known, double value) {
		private static NumberEstimate known(double value) {
			return new NumberEstimate(true, value);
		}

		private static NumberEstimate unknown() {
			return new NumberEstimate(false, 1.0);
		}
	}

	private record RollEstimate(boolean known, double expectedRolls, int exactRolls) {
		private static RollEstimate one() {
			return new RollEstimate(true, 1.0, 1);
		}

		private static RollEstimate unknown() {
			return new RollEstimate(false, 1.0, -1);
		}
	}

	private record DropEstimate(boolean known, double dropProbability, double expectedDropsPerBreak) {
	}

	private static final class MutableRule {
		private final String blockId;
		private final String outputItemId;
		private final LinkedHashSet<String> tools;
		private boolean emptyHandAllowed;
		private boolean probabilistic;
		private final String lootTableId;
		private boolean dropEstimateKnown;
		private double dropProbability;
		private double expectedDropsPerBreak;
		private int emptyHandBreakTicks;
		private final LinkedHashMap<String, Integer> breakTicksByToolItemId;

		private MutableRule(
			String blockId,
			String outputItemId,
			LinkedHashSet<String> tools,
			boolean emptyHandAllowed,
			boolean probabilistic,
			String lootTableId,
			DropEstimate dropEstimate,
			int emptyHandBreakTicks,
			Map<String, Integer> breakTicksByToolItemId
		) {
			this.blockId = blockId;
			this.outputItemId = outputItemId;
			this.tools = tools;
			this.emptyHandAllowed = emptyHandAllowed;
			this.probabilistic = probabilistic;
			this.lootTableId = lootTableId;
			this.dropEstimateKnown = dropEstimate.known();
			this.dropProbability = positiveOrOne(dropEstimate.dropProbability());
			this.expectedDropsPerBreak = positiveOrOne(dropEstimate.expectedDropsPerBreak());
			this.emptyHandBreakTicks = emptyHandBreakTicks;
			this.breakTicksByToolItemId = new LinkedHashMap<>(breakTicksByToolItemId);
		}

		private void mergeEstimate(DropEstimate estimate) {
			dropEstimateKnown &= estimate.known();
			dropProbability = Math.min(dropProbability, positiveOrOne(estimate.dropProbability()));
			expectedDropsPerBreak = Math.min(
				expectedDropsPerBreak,
				positiveOrOne(estimate.expectedDropsPerBreak())
			);
		}

		private static double positiveOrOne(double value) {
			return Double.isFinite(value) && value > 0.0 ? value : 1.0;
		}

		private BlockAcquisitionRule toRule() {
			return new BlockAcquisitionRule(
				blockId,
				outputItemId,
				tools.stream().sorted().toList(),
				emptyHandAllowed,
				probabilistic,
				lootTableId,
				dropEstimateKnown,
				dropProbability,
				expectedDropsPerBreak,
				emptyHandBreakTicks,
				breakTicksByToolItemId
			);
		}
	}
}
