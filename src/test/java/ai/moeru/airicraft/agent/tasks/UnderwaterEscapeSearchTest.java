package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderwaterEscapeSearchTest {
	@Test
	void derivesPathBudgetFromRemainingAir() {
		assertEquals(1, UnderwaterEscapeSearch.maxPathStepsForAir(20));
		assertEquals(10, UnderwaterEscapeSearch.maxPathStepsForAir(80));
		assertEquals(32, UnderwaterEscapeSearch.maxPathStepsForAir(300));
	}

	@Test
	void findsLateralBreathableCellUnderBlockedCeiling() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position middle = position(1, 40, 0);
		UnderwaterEscapeSearch.Position air = position(2, 40, 0);
		Map<UnderwaterEscapeSearch.Position, UnderwaterEscapeSearch.Cell> cells = new HashMap<>();
		cells.put(start, UnderwaterEscapeSearch.Cell.submerged());
		cells.put(middle, UnderwaterEscapeSearch.Cell.submerged());
		cells.put(air, UnderwaterEscapeSearch.Cell.breathableWater());

		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 4, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(cells)
		);

		assertEquals(UnderwaterEscapeSearch.SearchStatus.COMPLETE, result.status());
		assertEquals(List.of(start, middle, air), result.candidates().getFirst().route());
		assertEquals(2, result.candidates().getFirst().pathSteps());
	}

	@Test
	void rejectsBreathableCellBehindUnobservedSolidBlock() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position unreachableAir = position(2, 40, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 4, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				unreachableAir, UnderwaterEscapeSearch.Cell.breathableDry()
			))
		);

		assertTrue(result.candidates().isEmpty());
	}

	@Test
	void doesNotTraverseWaterCellsWithoutTwoBlockCollisionClearance() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position blockedWater = position(1, 40, 0);
		UnderwaterEscapeSearch.Position air = position(2, 40, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 4, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				blockedWater, new UnderwaterEscapeSearch.Cell(false, true, true, false, false),
				air, UnderwaterEscapeSearch.Cell.breathableDry()
			))
		);

		assertTrue(result.candidates().isEmpty());
	}

	@Test
	void acceptsCaveShoreWithoutSkyVisibility() {
		UnderwaterEscapeSearch.Position start = position(0, 30, 0);
		UnderwaterEscapeSearch.Position shore = position(1, 30, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.SAFE_STANDING, 2, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				shore, UnderwaterEscapeSearch.Cell.safeStandingCell()
			))
		);

		assertEquals(shore, result.candidates().getFirst().target());
		assertEquals(UnderwaterEscapeSearch.SearchMode.SAFE_STANDING, result.candidates().getFirst().mode());
	}

	@Test
	void followsBreathableSurfaceWaterToSafeStandingShore() {
		UnderwaterEscapeSearch.Position start = position(0, 30, 0);
		UnderwaterEscapeSearch.Position surfaceWater = position(1, 30, 0);
		UnderwaterEscapeSearch.Position shore = position(2, 30, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.SAFE_STANDING, 3, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				surfaceWater, UnderwaterEscapeSearch.Cell.breathableWater(),
				shore, UnderwaterEscapeSearch.Cell.safeStandingCell()
			))
		);

		assertEquals(List.of(start, surfaceWater, shore), result.candidates().getFirst().route());
	}

	@Test
	void stepsUpFromSurfaceWaterToOneBlockHighSafeStandingShore() {
		UnderwaterEscapeSearch.Position start = position(0, 30, 0);
		UnderwaterEscapeSearch.Position surfaceWater = position(1, 30, 0);
		UnderwaterEscapeSearch.Position raisedShore = position(2, 31, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.SAFE_STANDING, 3, 64),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				surfaceWater, UnderwaterEscapeSearch.Cell.breathableWater(),
				raisedShore, UnderwaterEscapeSearch.Cell.safeStandingCell()
			))
		);

		assertEquals(List.of(start, surfaceWater, raisedShore), result.candidates().getFirst().route());
	}

	@Test
	void enforcesMaximumPathSteps() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position one = position(1, 40, 0);
		UnderwaterEscapeSearch.Position two = position(2, 40, 0);
		UnderwaterEscapeSearch.Position air = position(3, 40, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.search(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 2, 32),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				one, UnderwaterEscapeSearch.Cell.submerged(),
				two, UnderwaterEscapeSearch.Cell.submerged(),
				air, UnderwaterEscapeSearch.Cell.breathableWater()
			))
		);

		assertTrue(result.candidates().isEmpty());
	}

	@Test
	void observesCellsIncrementallyWithinPerTickBudget() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position one = position(1, 40, 0);
		UnderwaterEscapeSearch.Position two = position(2, 40, 0);
		UnderwaterEscapeSearch.SearchSession session = UnderwaterEscapeSearch.begin(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 4, 8),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				one, UnderwaterEscapeSearch.Cell.submerged(),
				two, UnderwaterEscapeSearch.Cell.submerged()
			))
		);

		UnderwaterEscapeSearch.SearchUpdate first = session.advance(1);
		UnderwaterEscapeSearch.SearchUpdate second = session.advance(1);

		assertEquals(UnderwaterEscapeSearch.SearchStatus.SEARCHING, first.status());
		assertEquals(1, first.inspectedCellsThisStep());
		assertEquals(1, first.totalInspectedCells());
		assertEquals(1, second.inspectedCellsThisStep());
		assertEquals(2, second.totalInspectedCells());
	}

	@Test
	void reportsTotalCellInspectionBudgetExhaustion() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.Position next = position(1, 40, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.begin(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 4, 1),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged(),
				next, UnderwaterEscapeSearch.Cell.submerged()
			))
		).advance(UnderwaterEscapeSearch.DEFAULT_CELL_INSPECTIONS_PER_TICK);

		assertEquals(UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED, result.status());
		assertEquals(1, result.totalInspectedCells());
	}

	@Test
	void completingTheFinalNeighborAtTheExactBudgetReportsComplete() {
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.SearchUpdate result = UnderwaterEscapeSearch.begin(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 1, 11),
			new UnderwaterEscapeSearch.WorldSnapshot(Map.of(
				start, UnderwaterEscapeSearch.Cell.submerged()
			))
		).advance(11);

		assertEquals(UnderwaterEscapeSearch.SearchStatus.COMPLETE, result.status());
		assertEquals(11, result.totalInspectedCells());
	}

	@Test
	void beginDoesNotEagerlyReadTheWorldAndAdvanceCapsLiveObservations() {
		AtomicInteger observations = new AtomicInteger();
		UnderwaterEscapeSearch.Position start = position(0, 40, 0);
		UnderwaterEscapeSearch.SearchSession session = UnderwaterEscapeSearch.begin(
			request(start, UnderwaterEscapeSearch.SearchMode.BREATHABLE, 32, 4_096),
			position -> {
				observations.incrementAndGet();
				return UnderwaterEscapeSearch.Cell.submerged();
			}
		);

		assertEquals(0, observations.get());
		UnderwaterEscapeSearch.SearchUpdate update = session.advance(7);

		assertEquals(7, observations.get());
		assertEquals(7, update.inspectedCellsThisStep());
	}

	private static UnderwaterEscapeSearch.SearchRequest request(
		UnderwaterEscapeSearch.Position start,
		UnderwaterEscapeSearch.SearchMode mode,
		int maxPathSteps,
		int maxInspectedCells
	) {
		return new UnderwaterEscapeSearch.SearchRequest(start, mode, maxPathSteps, 4, maxInspectedCells);
	}

	private static UnderwaterEscapeSearch.Position position(int x, int y, int z) {
		return new UnderwaterEscapeSearch.Position(x, y, z);
	}
}
