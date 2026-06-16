package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.WorldEvidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmBootstrapFactProviderTest {
	@Test
	void emitsToolAndNearbyFarmFactsFromWorldEvidence() {
		ActionResolverContext context = new ActionResolverContext("world-a", "companion", "minecraft:overworld", 100);
		WorldEvidence evidence = new WorldEvidence(
			Map.of(),
			Map.of("minecraft:iron_hoe", 1, "minecraft:wheat_seeds", 3),
			Map.of(
				"minecraft:grass_block", 12,
				"minecraft:water", 3,
				"minecraft:farmland", 2,
				"minecraft:short_grass", 5
			),
			List.of(),
			List.of(),
			List.of(),
			"minecraft:overworld",
			10,
			64,
			-4,
			"minecraft:iron_hoe",
			0,
			List.of("0=minecraft:iron_hoex1"),
			100
		);

		List<ActionFact> facts = FarmBootstrapFactProvider.fromWorldEvidence(context, evidence);

		assertTrue(hasFact(facts, ActionFactType.INVENTORY_TOOL));
		assertTrue(hasFact(facts, ActionFactType.WORLD_FARM_SITE));
		assertTrue(hasFact(facts, ActionFactType.WORLD_FARM_PLOT));
		assertTrue(hasFact(facts, ActionFactType.WORLD_SOIL_CANDIDATE));
		assertTrue(hasFact(facts, ActionFactType.WORLD_HYDRATION_SOURCE));
		assertTrue(hasFact(facts, ActionFactType.WORLD_CROP_SEED_SOURCE));
		ActionFact tool = find(facts, ActionFactType.INVENTORY_TOOL);
		assertEquals("minecraft:hoes", tool.identity().keys().get("toolTag"));
		assertEquals(List.of("till_soil"), tool.payload().get("capabilities"));
		assertEquals(180L, tool.staleAfterTick());
	}

	@Test
	void returnsNoFactsWithoutEvidence() {
		ActionResolverContext context = new ActionResolverContext("world-a", "companion", "minecraft:overworld", 100);
		WorldEvidence evidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), "minecraft:overworld", 0, 64, 0, "minecraft:air", 100);

		assertTrue(FarmBootstrapFactProvider.fromWorldEvidence(context, evidence).isEmpty());
		assertTrue(FarmBootstrapFactProvider.fromWorldEvidence(null, evidence).isEmpty());
		assertTrue(FarmBootstrapFactProvider.fromWorldEvidence(context, null).isEmpty());
	}

	@Test
	void doesNotEmitToolFactForNonHoeInventory() {
		ActionResolverContext context = new ActionResolverContext("world-a", "companion", "minecraft:overworld", 100);
		WorldEvidence evidence = new WorldEvidence(
			Map.of(),
			Map.of("minecraft:stick", 4),
			Map.of("minecraft:water", 1),
			"minecraft:overworld",
			0,
			64,
			0,
			"minecraft:stick",
			100
		);

		List<ActionFact> facts = FarmBootstrapFactProvider.fromWorldEvidence(context, evidence);

		assertFalse(hasFact(facts, ActionFactType.INVENTORY_TOOL));
		assertTrue(hasFact(facts, ActionFactType.WORLD_HYDRATION_SOURCE));
	}

	private static boolean hasFact(List<ActionFact> facts, ActionFactType type) {
		return facts.stream().anyMatch(fact -> fact.identity().type() == type);
	}

	private static ActionFact find(List<ActionFact> facts, ActionFactType type) {
		return facts.stream()
			.filter(fact -> fact.identity().type() == type)
			.findFirst()
			.orElseThrow();
	}
}
