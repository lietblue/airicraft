package ai.moeru.airicraft.agent.actions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable lookup index consumed by the deterministic action resolver and mining executors.
 */
public final class BlockAcquisitionIndex {
	private static final BlockAcquisitionIndex EMPTY = new BlockAcquisitionIndex(List.of());

	private final List<BlockAcquisitionRule> rules;
	private final Map<String, List<BlockAcquisitionRule>> byOutputItemId;
	private final Map<String, Set<String>> outputItemIdsByBlockId;

	private BlockAcquisitionIndex(List<BlockAcquisitionRule> rules) {
		ArrayList<BlockAcquisitionRule> ordered = new ArrayList<>(rules == null ? List.of() : rules);
		ordered.sort(Comparator
			.comparing(BlockAcquisitionRule::outputItemId)
			.thenComparing(BlockAcquisitionRule::blockId)
			.thenComparing(rule -> String.join("\u0000", rule.usableToolItemIds()))
			.thenComparing(BlockAcquisitionRule::emptyHandAllowed));
		this.rules = List.copyOf(ordered);

		LinkedHashMap<String, List<BlockAcquisitionRule>> outputRules = new LinkedHashMap<>();
		LinkedHashMap<String, LinkedHashSet<String>> blockOutputs = new LinkedHashMap<>();
		for (BlockAcquisitionRule rule : this.rules) {
			outputRules.computeIfAbsent(rule.outputItemId(), ignored -> new ArrayList<>()).add(rule);
			blockOutputs.computeIfAbsent(rule.blockId(), ignored -> new LinkedHashSet<>()).add(rule.outputItemId());
		}
		LinkedHashMap<String, List<BlockAcquisitionRule>> frozenOutputRules = new LinkedHashMap<>();
		outputRules.forEach((key, value) -> frozenOutputRules.put(key, List.copyOf(value)));
		this.byOutputItemId = Collections.unmodifiableMap(frozenOutputRules);
		LinkedHashMap<String, Set<String>> frozenBlockOutputs = new LinkedHashMap<>();
		blockOutputs.forEach((key, value) -> frozenBlockOutputs.put(key, Collections.unmodifiableSet(value)));
		this.outputItemIdsByBlockId = Collections.unmodifiableMap(frozenBlockOutputs);
	}

	public static BlockAcquisitionIndex empty() {
		return EMPTY;
	}

	public static BlockAcquisitionIndex of(List<BlockAcquisitionRule> rules) {
		if (rules == null || rules.isEmpty()) {
			return empty();
		}
		return new BlockAcquisitionIndex(rules);
	}

	public List<BlockAcquisitionRule> rules() {
		return rules;
	}

	public List<BlockAcquisitionRule> rulesForOutput(String itemId) {
		return byOutputItemId.getOrDefault(normalize(itemId), List.of());
	}

	public List<String> sourceBlockIdsForOutput(String itemId) {
		return rulesForOutput(itemId).stream()
			.map(BlockAcquisitionRule::blockId)
			.distinct()
			.sorted()
			.toList();
	}

	public List<String> sourceBlockIdsForOutputs(List<String> itemIds) {
		if (itemIds == null || itemIds.isEmpty()) {
			return List.of();
		}
		return itemIds.stream()
			.flatMap(itemId -> sourceBlockIdsForOutput(itemId).stream())
			.distinct()
			.sorted()
			.toList();
	}

	public Set<String> matchingOutputItemIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return Set.of();
		}
		LinkedHashSet<String> outputItemIds = new LinkedHashSet<>();
		blockIds.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.sorted()
			.forEach(blockId -> outputItemIds.addAll(outputItemIdsByBlockId.getOrDefault(blockId, Set.of())));
		return Collections.unmodifiableSet(outputItemIds);
	}

	public boolean isEmpty() {
		return rules.isEmpty();
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}
}
