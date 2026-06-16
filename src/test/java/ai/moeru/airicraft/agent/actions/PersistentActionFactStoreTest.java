package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentActionFactStoreTest {
	@TempDir
	private Path tempDir;

	@Test
	void persistsDurableWorldFactsByWorldScope() {
		PersistentActionFactStore store = new PersistentActionFactStore(tempDir);
		ActionFact crop = new ActionFact(
			ActionFactIdentity.worldCropGroup("world-a", "minecraft:overworld", "farm-1", "minecraft:wheat"),
			Map.of("matureCount", 3, "totalCount", 9),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		);

		assertEquals(1, store.upsert("world-a", crop));

		List<ActionFact> worldA = store.list("world-a");
		List<ActionFact> worldB = store.list("world-b");
		assertEquals(1, worldA.size());
		assertEquals(ActionFactType.WORLD_CROP_GROUP, worldA.getFirst().identity().type());
		assertTrue(worldB.isEmpty());
	}

	@Test
	void persistsDurableFarmFactsButNotFiniteFreshnessObservations() {
		PersistentActionFactStore store = new PersistentActionFactStore(tempDir);
		ActionFact durableSite = new ActionFact(
			ActionFactIdentity.worldFarmSite("world-a", "minecraft:overworld", "farm-1"),
			Map.of("siteKind", "farm"),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		);
		ActionFact nearbySoil = new ActionFact(
			ActionFactIdentity.worldSoilCandidate("world-a", "minecraft:overworld", "farm-1", "nearby-soil"),
			Map.of("blockCounts", Map.of("minecraft:grass_block", 12)),
			ActionFactProvenance.OBSERVED,
			100,
			180
		);

		assertEquals(1, store.save("world-a", List.of(durableSite, nearbySoil)));

		List<ActionFact> worldA = store.list("world-a");
		assertEquals(1, worldA.size());
		assertEquals(ActionFactType.WORLD_FARM_SITE, worldA.getFirst().identity().type());
		assertEquals(ActionFactDurability.PERSISTENT, ActionFactPersistencePolicy.durability(durableSite));
		assertEquals(ActionFactDurability.VOLATILE, ActionFactPersistencePolicy.durability(nearbySoil));
	}

	@Test
	void doesNotPersistVolatileInventoryOrCraftRecipeFactsByDefault() {
		PersistentActionFactStore store = new PersistentActionFactStore(tempDir);
		ActionFact inventory = new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			100,
			ActionFact.NEVER_STALE
		);
		ActionFact craftRecipe = new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of("outputItemId", "minecraft:bread", "outputCount", 1, "inputCounts", Map.of("minecraft:wheat", 3)),
			ActionFactProvenance.OBSERVED,
			100,
			101
		);

		assertEquals(0, store.save("world-a", List.of(inventory, craftRecipe)));

		assertTrue(store.list("world-a").isEmpty());
		assertEquals(ActionFactDurability.VOLATILE, ActionFactPersistencePolicy.durability(inventory));
		assertEquals(ActionFactDurability.VOLATILE, ActionFactPersistencePolicy.durability(craftRecipe));
	}

	@Test
	void reloadsPersistedFactsIntoInMemoryStore() {
		PersistentActionFactStore store = new PersistentActionFactStore(tempDir);
		ActionFact site = new ActionFact(
			ActionFactIdentity.worldSite("world-a", "minecraft:overworld", "farm-1"),
			Map.of("kind", "farm"),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		);

		store.upsert("world-a", site);
		ActionFactStore loaded = new PersistentActionFactStore(tempDir).load("world-a");

		assertEquals(1, loaded.size());
		assertTrue(loaded.find(site.identity()).isPresent());
	}

	@Test
	void clearRemovesOnlyRequestedWorldFile() {
		PersistentActionFactStore store = new PersistentActionFactStore(tempDir);
		store.upsert("world-a", new ActionFact(
			ActionFactIdentity.worldSite("world-a", "minecraft:overworld", "farm-1"),
			Map.of("kind", "farm"),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		));
		store.upsert("world-b", new ActionFact(
			ActionFactIdentity.worldSite("world-b", "minecraft:overworld", "farm-1"),
			Map.of("kind", "farm"),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		));

		assertEquals(1, store.clear("world-a"));

		assertTrue(store.list("world-a").isEmpty());
		assertFalse(store.list("world-b").isEmpty());
	}
}
