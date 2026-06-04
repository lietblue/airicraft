package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltingTaskExecutorTest {
	@Test
	void fuelItemsNeededRoundsUpCookTime() {
		assertEquals(0, SmeltingTaskExecutor.fuelItemsNeeded(0, 1600));
		assertEquals(1, SmeltingTaskExecutor.fuelItemsNeeded(200, 1600));
		assertEquals(2, SmeltingTaskExecutor.fuelItemsNeeded(1800, 1600));
	}

	@Test
	void remainingItemsToMoveCountsMatchingTargetSlotContents() {
		assertEquals(3, SmeltingTaskExecutor.remainingItemsToMove(null, 0, "minecraft:raw_iron", 3));
		assertEquals(1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 2, "minecraft:raw_iron", 3));
		assertEquals(0, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 3, "minecraft:raw_iron", 3));
		assertEquals(-1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:coal", 1, "minecraft:raw_iron", 3));
	}
}
