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
				for (JsonElement entryElement : array(pool, "entries")) {
					if (entryElement.isJsonObject()) {
						compileEntry(source, entryElement.getAsJsonObject(), inheritedConditions, normalizedTags, compiled);
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
		Map<String, List<String>> itemTags,
		Map<String, MutableRule> compiled
	) {
		ArrayList<JsonObject> allConditions = new ArrayList<>(inheritedConditions);
		allConditions.addAll(conditions(entry));
		String type = string(entry, "type");
		if ("minecraft:item".equals(type)) {
			String outputItemId = string(entry, "name");
			if (!outputItemId.isBlank()) {
				compileOutput(source, outputItemId, allConditions, itemTags, compiled);
			}
		}
		else if ("minecraft:tag".equals(type)) {
			String tagId = withoutTagPrefix(string(entry, "name"));
			for (String outputItemId : itemTags.getOrDefault(tagId, List.of())) {
				compileOutput(source, outputItemId, allConditions, itemTags, compiled);
			}
		}
		for (String childField : List.of("children", "entries")) {
			for (JsonElement child : array(entry, childField)) {
				if (child.isJsonObject()) {
					compileEntry(source, child.getAsJsonObject(), allConditions, itemTags, compiled);
				}
			}
		}
	}

	private static void compileOutput(
		BlockLootTableSource source,
		String outputItemId,
		List<JsonObject> conditions,
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
			tools = List.of();
			emptyHandAllowed = true;
		}
		if (!emptyHandAllowed && tools.isEmpty()) {
			return;
		}
		boolean probabilistic = conditions.stream().anyMatch(BlockLootTableCompiler::containsRandomCondition);
		String key = source.blockId() + "\u0000" + outputItemId;
		MutableRule existing = compiled.get(key);
		if (existing == null) {
			compiled.put(key, new MutableRule(
				source.blockId(),
				outputItemId,
				new LinkedHashSet<>(tools),
				emptyHandAllowed,
				probabilistic,
				source.lootTableId()
			));
			return;
		}
		existing.tools.addAll(tools);
		existing.emptyHandAllowed |= emptyHandAllowed;
		existing.probabilistic &= probabilistic;
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

	private static boolean containsRandomCondition(JsonObject condition) {
		String type = string(condition, "condition");
		if ("minecraft:random_chance".equals(type)
			|| "minecraft:random_chance_with_enchanted_bonus".equals(type)
			|| "minecraft:table_bonus".equals(type)) {
			return true;
		}
		for (String field : List.of("terms")) {
			for (JsonElement child : array(condition, field)) {
				if (child.isJsonObject() && containsRandomCondition(child.getAsJsonObject())) {
					return true;
				}
			}
		}
		JsonObject term = object(condition, "term");
		return !term.entrySet().isEmpty() && containsRandomCondition(term);
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

	private static final class MutableRule {
		private final String blockId;
		private final String outputItemId;
		private final LinkedHashSet<String> tools;
		private boolean emptyHandAllowed;
		private boolean probabilistic;
		private final String lootTableId;

		private MutableRule(
			String blockId,
			String outputItemId,
			LinkedHashSet<String> tools,
			boolean emptyHandAllowed,
			boolean probabilistic,
			String lootTableId
		) {
			this.blockId = blockId;
			this.outputItemId = outputItemId;
			this.tools = tools;
			this.emptyHandAllowed = emptyHandAllowed;
			this.probabilistic = probabilistic;
			this.lootTableId = lootTableId;
		}

		private BlockAcquisitionRule toRule() {
			return new BlockAcquisitionRule(
				blockId,
				outputItemId,
				tools.stream().sorted().toList(),
				emptyHandAllowed,
				probabilistic,
				lootTableId
			);
		}
	}
}
