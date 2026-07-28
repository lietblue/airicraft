package ai.moeru.airicraft.agent.llm;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrentWorldQueryServiceTest {
	@Test
	void areaResultBoundsOutputAndOnlyAuthorizesReturnedPositions() {
		BlockPos nearest = new BlockPos(1, 64, 1);
		BlockPos second = new BlockPos(2, 64, 2);
		BlockPos omitted = new BlockPos(3, 64, 3);
		List<CurrentWorldQueryService.BlockRecord> records = List.of(
			record(omitted, 3),
			record(nearest, 1),
			record(second, 2)
		);

		CurrentWorldQueryService.WorldQueryResult result = CurrentWorldQueryService.areaResult(
			"center",
			"0,63,0..4,65,4",
			75,
			records,
			2
		);

		assertTrue(result.text().contains("scanned=75 matched=3 returned=2"), result.text());
		assertTrue(result.text().contains("pos=1,64,1"), result.text());
		assertTrue(result.text().contains("pos=2,64,2"), result.text());
		assertFalse(result.text().contains("pos=3,64,3"), result.text());
		assertEquals(List.of(nearest, second), result.observedPositions());
	}

	private static CurrentWorldQueryService.BlockRecord record(BlockPos pos, int distance) {
		return new CurrentWorldQueryService.BlockRecord(
			pos,
			"minecraft:stone",
			Map.of(),
			true,
			false,
			false,
			false,
			distance
		);
	}
}
