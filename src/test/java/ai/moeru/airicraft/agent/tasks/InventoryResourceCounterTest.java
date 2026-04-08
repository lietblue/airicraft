package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryResourceCounterTest {
	@Test
	void acceptsWoodLogItemIdsAcrossSpecies() {
		assertTrue(InventoryResourceCounter.accepts(TaskResourceKind.WOOD_LOGS, "minecraft:oak_log"));
		assertTrue(InventoryResourceCounter.accepts(TaskResourceKind.WOOD_LOGS, "minecraft:birch_log"));
		assertFalse(InventoryResourceCounter.accepts(TaskResourceKind.WOOD_LOGS, "minecraft:cobblestone"));
	}
}
