package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinedBlockDropMapperTest {
	@Test
	void countsCommonMinedDropsByBlockId() {
		assertEquals(3, MinedBlockDropMapper.matchingInventoryItemCount(
			Map.of("minecraft:raw_iron", 3),
			List.of("minecraft:iron_ore")
		));
		assertEquals(8, MinedBlockDropMapper.matchingInventoryItemCount(
			Map.of("minecraft:cobblestone", 8),
			List.of("minecraft:stone")
		));
	}

	@Test
	void keepsSelfDropBlockItemsAsMatches() {
		assertEquals(2, MinedBlockDropMapper.matchingInventoryItemCount(
			Map.of("minecraft:dirt", 2),
			List.of("minecraft:dirt")
		));
		assertTrue(MinedBlockDropMapper.matchingInventoryItemIds(List.of("minecraft:iron_ore")).contains("minecraft:raw_iron"));
	}
}
