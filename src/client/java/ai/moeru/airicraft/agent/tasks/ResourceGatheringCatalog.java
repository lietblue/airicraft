package ai.moeru.airicraft.agent.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ResourceGatheringCatalog {
	private static final Map<TaskResourceKind, ResourceEntry> BY_KIND = buildByKind();
	private static final Map<String, List<String>> DROPS_BY_BLOCK_ID = buildDropsByBlockId();

	private ResourceGatheringCatalog() {
	}

	public static List<TaskResourceKind> supportedKinds() {
		return List.copyOf(BY_KIND.keySet());
	}

	public static List<String> supportedKindNames() {
		return supportedKinds().stream()
			.map(TaskResourceKind::name)
			.toList();
	}

	public static Optional<ResourceEntry> entry(TaskResourceKind kind) {
		return Optional.ofNullable(BY_KIND.get(kind));
	}

	public static Optional<ResourceEntry> entry(String resourceKind) {
		if (resourceKind == null || resourceKind.isBlank()) {
			return Optional.empty();
		}
		try {
			return entry(TaskResourceKind.valueOf(resourceKind.trim().toUpperCase(java.util.Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	public static boolean accepts(TaskResourceKind kind, String itemId) {
		return entry(kind)
			.map(entry -> entry.acceptedItemIds().contains(normalize(itemId)))
			.orElse(false);
	}

	public static List<String> targetBlockIds(TaskResourceKind kind) {
		return entry(kind)
			.map(ResourceEntry::sourceBlockIds)
			.orElse(List.of());
	}

	public static Set<String> matchingInventoryItemIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return Set.of();
		}
		LinkedHashSet<String> itemIds = new LinkedHashSet<>();
		for (String blockId : blockIds) {
			String normalized = normalize(blockId);
			if (normalized.isBlank()) {
				continue;
			}
			itemIds.add(normalized);
			itemIds.addAll(DROPS_BY_BLOCK_ID.getOrDefault(normalized, List.of()));
		}
		return Collections.unmodifiableSet(itemIds);
	}

	public static List<String> sourceBlockIdsForInventoryItem(String itemId) {
		String normalized = normalize(itemId);
		if (normalized.isBlank()) {
			return List.of();
		}
		LinkedHashSet<String> blockIds = new LinkedHashSet<>();
		DROPS_BY_BLOCK_ID.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.filter(entry -> entry.getValue().contains(normalized))
			.map(Map.Entry::getKey)
			.forEach(blockIds::add);
		return List.copyOf(blockIds);
	}

	private static Map<TaskResourceKind, ResourceEntry> buildByKind() {
		EnumMap<TaskResourceKind, ResourceEntry> entries = new EnumMap<>(TaskResourceKind.class);
		put(entries, aggregate(TaskResourceKind.WOOD_LOGS, List.of(
			"minecraft:oak_log",
			"minecraft:birch_log",
			"minecraft:spruce_log",
			"minecraft:jungle_log",
			"minecraft:acacia_log",
			"minecraft:dark_oak_log",
			"minecraft:mangrove_log",
			"minecraft:cherry_log",
			"minecraft:pale_oak_log"
		), List.of(
			"minecraft:oak_log",
			"minecraft:birch_log",
			"minecraft:spruce_log",
			"minecraft:jungle_log",
			"minecraft:acacia_log",
			"minecraft:dark_oak_log",
			"minecraft:mangrove_log",
			"minecraft:cherry_log",
			"minecraft:pale_oak_log"
		)));
		put(entries, itemBacked(TaskResourceKind.DIRT, "minecraft:dirt", List.of("minecraft:dirt", "minecraft:grass_block")));
		put(entries, itemBacked(TaskResourceKind.COBBLESTONE, "minecraft:cobblestone", List.of("minecraft:stone")));
		put(entries, itemBacked(TaskResourceKind.COAL, "minecraft:coal", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")));
		put(entries, itemBacked(TaskResourceKind.RAW_IRON, "minecraft:raw_iron", List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore")));
		put(entries, itemBacked(TaskResourceKind.RAW_COPPER, "minecraft:raw_copper", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")));
		put(entries, itemBacked(TaskResourceKind.RAW_GOLD, "minecraft:raw_gold", List.of("minecraft:deepslate_gold_ore", "minecraft:gold_ore")));
		put(entries, itemBacked(TaskResourceKind.DIAMOND, "minecraft:diamond", List.of("minecraft:deepslate_diamond_ore", "minecraft:diamond_ore")));
		put(entries, itemBacked(TaskResourceKind.EMERALD, "minecraft:emerald", List.of("minecraft:deepslate_emerald_ore", "minecraft:emerald_ore")));
		put(entries, itemBacked(TaskResourceKind.REDSTONE, "minecraft:redstone", List.of("minecraft:deepslate_redstone_ore", "minecraft:redstone_ore")));
		put(entries, itemBacked(TaskResourceKind.LAPIS_LAZULI, "minecraft:lapis_lazuli", List.of("minecraft:deepslate_lapis_ore", "minecraft:lapis_ore")));
		put(entries, itemBacked(TaskResourceKind.QUARTZ, "minecraft:quartz", List.of("minecraft:nether_quartz_ore")));
		return Collections.unmodifiableMap(entries);
	}

	private static Map<String, List<String>> buildDropsByBlockId() {
		LinkedHashSet<String> blockIds = new LinkedHashSet<>();
		for (ResourceEntry entry : BY_KIND.values()) {
			blockIds.addAll(entry.sourceBlockIds());
		}
		java.util.LinkedHashMap<String, List<String>> drops = new java.util.LinkedHashMap<>();
		for (String blockId : blockIds) {
			ArrayList<String> itemIds = new ArrayList<>();
			for (ResourceEntry entry : BY_KIND.values()) {
				if (entry.sourceBlockIds().contains(blockId)) {
					itemIds.addAll(entry.acceptedItemIds());
				}
			}
			drops.put(blockId, List.copyOf(new LinkedHashSet<>(itemIds)));
		}
		return Collections.unmodifiableMap(drops);
	}

	private static ResourceEntry aggregate(TaskResourceKind kind, List<String> acceptedItemIds, List<String> sourceBlockIds) {
		return new ResourceEntry(kind, acceptedItemIds, sourceBlockIds, "", true);
	}

	private static ResourceEntry itemBacked(TaskResourceKind kind, String primaryItemId, List<String> sourceBlockIds) {
		return new ResourceEntry(kind, List.of(primaryItemId), sourceBlockIds, primaryItemId, false);
	}

	private static void put(Map<TaskResourceKind, ResourceEntry> entries, ResourceEntry entry) {
		entries.put(entry.kind(), entry);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	public record ResourceEntry(
		TaskResourceKind kind,
		List<String> acceptedItemIds,
		List<String> sourceBlockIds,
		String primaryItemId,
		boolean aggregate
	) {
		public ResourceEntry {
			acceptedItemIds = List.copyOf(acceptedItemIds == null ? List.of() : acceptedItemIds);
			sourceBlockIds = List.copyOf(sourceBlockIds == null ? List.of() : sourceBlockIds);
			primaryItemId = primaryItemId == null ? "" : primaryItemId;
		}
	}
}
