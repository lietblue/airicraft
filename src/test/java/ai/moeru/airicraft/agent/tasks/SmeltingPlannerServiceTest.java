package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingPlannerServiceTest {
	@Test
	void effectiveCookTimeKeepsFurnaceRecipesAtFullFurnaceDuration() {
		assertEquals(200, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.BLAST_FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.SMOKER));
	}

	@Test
	void furnacePlacementCandidatesIncludeSimpleVerticalOffsets() {
		BlockPos origin = new BlockPos(15, 62, 10);
		List<BlockPos> candidates = SmeltingPlannerService.furnacePlacementCandidatePositions(origin);

		assertEquals(new BlockPos(15, 62, 9), candidates.get(0));
		assertTrue(candidates.contains(new BlockPos(15, 61, 9)));
		assertTrue(candidates.contains(new BlockPos(15, 63, 9)));
	}
}
