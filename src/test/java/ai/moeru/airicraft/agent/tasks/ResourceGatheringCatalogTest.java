package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGatheringCatalogTest {
	@Test
	void exposesCatalogedCoreResourceKinds() {
		assertEquals(List.of(
			"WOOD_LOGS",
			"DIRT",
			"COBBLESTONE",
			"COAL",
			"RAW_IRON",
			"RAW_COPPER",
			"RAW_GOLD",
			"DIAMOND",
			"EMERALD",
			"REDSTONE",
			"LAPIS_LAZULI",
			"QUARTZ"
		), ResourceGatheringCatalog.supportedKindNames());
	}

	@Test
	void definesAcceptedItemsAndSourceBlocks() {
		ResourceGatheringCatalog.ResourceEntry rawIron = ResourceGatheringCatalog.entry(TaskResourceKind.RAW_IRON).orElseThrow();
		assertFalse(rawIron.aggregate());
		assertEquals("minecraft:raw_iron", rawIron.primaryItemId());
		assertEquals(List.of("minecraft:raw_iron"), rawIron.acceptedItemIds());
		assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), rawIron.sourceBlockIds());

		ResourceGatheringCatalog.ResourceEntry wood = ResourceGatheringCatalog.entry(TaskResourceKind.WOOD_LOGS).orElseThrow();
		assertTrue(wood.aggregate());
		assertTrue(wood.acceptedItemIds().contains("minecraft:oak_log"));
		assertTrue(wood.sourceBlockIds().contains("minecraft:pale_oak_log"));
	}

	@Test
	void includesExplicitPossibleDropsOutsideResourceKinds() {
		assertEquals(
			Set.of("minecraft:short_grass", "minecraft:wheat_seeds"),
			ResourceGatheringCatalog.matchingInventoryItemIds(List.of("minecraft:short_grass"))
		);
		assertEquals(List.of(), ResourceGatheringCatalog.sourceBlockIdsForInventoryItem("minecraft:wheat_seeds"));
	}
}
