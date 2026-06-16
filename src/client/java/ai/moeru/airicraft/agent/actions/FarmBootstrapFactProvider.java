package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.WorldEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FarmBootstrapFactProvider {
	private static final long NEARBY_FACT_TTL_TICKS = 80L;
	private static final List<String> HOE_ITEMS = List.of(
		"minecraft:wooden_hoe",
		"minecraft:stone_hoe",
		"minecraft:iron_hoe",
		"minecraft:golden_hoe",
		"minecraft:diamond_hoe",
		"minecraft:netherite_hoe"
	);
	private static final List<String> SOIL_BLOCKS = List.of(
		"minecraft:grass_block",
		"minecraft:dirt",
		"minecraft:coarse_dirt",
		"minecraft:dirt_path"
	);
	private static final List<String> SEED_SOURCE_BLOCKS = List.of(
		"minecraft:grass",
		"minecraft:short_grass",
		"minecraft:tall_grass",
		"minecraft:fern",
		"minecraft:large_fern"
	);

	private FarmBootstrapFactProvider() {
	}

	public static List<ActionFact> fromWorldEvidence(ActionResolverContext context, WorldEvidence evidence) {
		if (context == null || evidence == null) {
			return List.of();
		}
		ArrayList<ActionFact> facts = new ArrayList<>();
		addHoeFact(context, evidence, facts);
		addNearbyFarmFacts(context, evidence, facts);
		return List.copyOf(facts);
	}

	private static void addHoeFact(ActionResolverContext context, WorldEvidence evidence, List<ActionFact> facts) {
		LinkedHashMap<String, Integer> hoeCounts = blockCounts(evidence.itemCounts(), HOE_ITEMS);
		int total = hoeCounts.values().stream().mapToInt(Integer::intValue).sum();
		if (total <= 0) {
			return;
		}
		facts.add(new ActionFact(
			ActionFactIdentity.inventoryTool(context.worldId(), context.actorId(), "minecraft:hoes"),
			Map.of(
				"count", total,
				"itemCounts", hoeCounts,
				"capabilities", List.of("till_soil"),
				"equippedItemId", emptyToUnknown(evidence.equippedItemId())
			),
			ActionFactProvenance.OBSERVED,
			context.currentTick(),
			context.currentTick() + NEARBY_FACT_TTL_TICKS
		));
	}

	private static void addNearbyFarmFacts(ActionResolverContext context, WorldEvidence evidence, List<ActionFact> facts) {
		Map<String, Integer> nearbyBlocks = evidence.nearbyBlocks();
		if (nearbyBlocks.isEmpty()) {
			return;
		}
		String siteId = nearbySiteId(evidence);
		String plotId = siteId + ":plot";
		long staleAfterTick = context.currentTick() + NEARBY_FACT_TTL_TICKS;
		LinkedHashMap<String, Integer> soilCounts = blockCounts(nearbyBlocks, SOIL_BLOCKS);
		int farmlandCount = nearbyBlocks.getOrDefault("minecraft:farmland", 0);
		int waterCount = nearbyBlocks.getOrDefault("minecraft:water", 0);
		LinkedHashMap<String, Integer> seedSourceCounts = blockCounts(nearbyBlocks, SEED_SOURCE_BLOCKS);

		if (farmlandCount > 0 || !soilCounts.isEmpty()) {
			facts.add(new ActionFact(
				ActionFactIdentity.worldFarmSite(context.worldId(), context.dimension(), siteId),
				Map.of(
					"siteKind", "nearby_farm_bootstrap",
					"origin", originPayload(evidence),
					"farmlandCount", farmlandCount,
					"soilCounts", soilCounts,
					"hydrationSourceCount", waterCount
				),
				ActionFactProvenance.INFERRED,
				context.currentTick(),
				staleAfterTick
			));
			facts.add(new ActionFact(
				ActionFactIdentity.worldFarmPlot(context.worldId(), context.dimension(), siteId, plotId),
				Map.of(
					"plotKind", farmlandCount > 0 ? "existing_farmland" : "soil_candidate_area",
					"farmlandCount", farmlandCount,
					"soilCounts", soilCounts
				),
				ActionFactProvenance.INFERRED,
				context.currentTick(),
				staleAfterTick
			));
		}

		if (!soilCounts.isEmpty()) {
			facts.add(new ActionFact(
				ActionFactIdentity.worldSoilCandidate(context.worldId(), context.dimension(), siteId, siteId + ":soil"),
				Map.of(
					"origin", originPayload(evidence),
					"blockCounts", soilCounts
				),
				ActionFactProvenance.OBSERVED,
				context.currentTick(),
				staleAfterTick
			));
		}
		if (waterCount > 0) {
			facts.add(new ActionFact(
				ActionFactIdentity.worldHydrationSource(context.worldId(), context.dimension(), siteId, siteId + ":water"),
				Map.of(
					"sourceKind", "nearby_water",
					"blockId", "minecraft:water",
					"count", waterCount
				),
				ActionFactProvenance.OBSERVED,
				context.currentTick(),
				staleAfterTick
			));
		}
		if (!seedSourceCounts.isEmpty()) {
			facts.add(new ActionFact(
				ActionFactIdentity.worldCropSeedSource(context.worldId(), context.dimension(), siteId, siteId + ":seed_source"),
				Map.of(
					"sourceKind", "nearby_vegetation",
					"blockCounts", seedSourceCounts,
					"cropItemId", "minecraft:wheat_seeds"
				),
				ActionFactProvenance.OBSERVED,
				context.currentTick(),
				staleAfterTick
			));
		}
	}

	private static LinkedHashMap<String, Integer> blockCounts(Map<String, Integer> source, List<String> ids) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		for (String id : ids) {
			int count = source.getOrDefault(id, 0);
			if (count > 0) {
				counts.put(id, count);
			}
		}
		return counts;
	}

	private static Map<String, Object> originPayload(WorldEvidence evidence) {
		return Map.of(
			"x", evidence.x(),
			"y", evidence.y(),
			"z", evidence.z()
		);
	}

	private static String nearbySiteId(WorldEvidence evidence) {
		return "nearby:" + evidence.x() + "," + evidence.y() + "," + evidence.z();
	}

	private static String emptyToUnknown(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}
}
