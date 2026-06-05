package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltingPlannerServiceTest {
	@Test
	void effectiveCookTimeKeepsFurnaceRecipesAtFullFurnaceDuration() {
		assertEquals(200, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.BLAST_FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.SMOKER));
	}
}
