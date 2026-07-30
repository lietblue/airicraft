package ai.moeru.airicraft.agent.tasks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic search policy for an underwater route to breathable space or safe ground.
 * Cell inspection is lazy and bounded; the live adapter memoizes every observation.
 */
public final class UnderwaterEscapeSearch {
	public static final int MAX_PATH_STEPS = 32;
	public static final int DEFAULT_MAX_CANDIDATES = 4;
	public static final int DEFAULT_CELL_INSPECTIONS_PER_TICK = 512;
	public static final int DEFAULT_MAX_INSPECTED_CELLS = 4_096;
	private static final List<Offset> NEIGHBOR_OFFSETS = List.of(
		new Offset(0, 1, 0),
		new Offset(-1, 0, 0),
		new Offset(0, 0, -1),
		new Offset(0, 0, 1),
		new Offset(1, 0, 0),
		new Offset(0, -1, 0)
	);

	private UnderwaterEscapeSearch() {
	}

	public static int maxPathStepsForAir(int remainingAirTicks) {
		return Math.min(MAX_PATH_STEPS, Math.max(1, (remainingAirTicks - 20) / 6));
	}

	public static SearchSession begin(SearchRequest request, CellView world) {
		return new SearchSession(request, world);
	}

	public static SearchUpdate search(SearchRequest request, CellView world) {
		return begin(request, world).advance(request.maxInspectedCells());
	}

	public enum SearchMode {
		BREATHABLE,
		SAFE_STANDING
	}

	public enum SearchStatus {
		SEARCHING,
		COMPLETE,
		CELL_BUDGET_EXHAUSTED
	}

	public record Position(int x, int y, int z) {
		public Position offset(int dx, int dy, int dz) {
			return new Position(x + dx, y + dy, z + dz);
		}
	}

	/**
	 * A stable observation of the two blocks occupied by a player at {@code Position}.
	 * Safe-standing is intentionally independent of sky visibility so cave shores are valid.
	 */
	public record Cell(
		boolean collisionFree,
		boolean waterAtFeet,
		boolean waterAtHead,
		boolean breathable,
		boolean safeStanding
	) {
		public Cell {
			if (breathable && !collisionFree) {
				throw new IllegalArgumentException("breathable cells must be collision-free");
			}
			if (safeStanding && !breathable) {
				throw new IllegalArgumentException("safe-standing cells must be breathable");
			}
		}

		public static Cell blocked() {
			return new Cell(false, false, false, false, false);
		}

		public static Cell submerged() {
			return new Cell(true, true, true, false, false);
		}

		public static Cell breathableWater() {
			return new Cell(true, true, false, true, false);
		}

		public static Cell breathableDry() {
			return new Cell(true, false, false, true, false);
		}

		public static Cell safeStandingCell() {
			return new Cell(true, false, false, true, true);
		}

		boolean waterRouteCell() {
			return collisionFree && waterAtFeet;
		}

		boolean matches(SearchMode mode) {
			return switch (mode) {
				case BREATHABLE -> breathable;
				case SAFE_STANDING -> safeStanding;
			};
		}
	}

	@FunctionalInterface
	public interface CellView {
		Cell cellAt(Position position);
	}

	public record WorldSnapshot(Map<Position, Cell> cells) implements CellView {
		public WorldSnapshot {
			Objects.requireNonNull(cells, "cells");
			cells = Map.copyOf(cells);
		}

		public Cell cellAt(Position position) {
			return cells.getOrDefault(position, Cell.blocked());
		}
	}

	public record SearchRequest(
		Position start,
		SearchMode mode,
		int maxPathSteps,
		int maxCandidates,
		int maxInspectedCells
	) {
		public SearchRequest {
			Objects.requireNonNull(start, "start");
			Objects.requireNonNull(mode, "mode");
			if (maxPathSteps < 1 || maxPathSteps > MAX_PATH_STEPS) {
				throw new IllegalArgumentException("maxPathSteps must be between 1 and " + MAX_PATH_STEPS);
			}
			if (maxCandidates < 1) {
				throw new IllegalArgumentException("maxCandidates must be positive");
			}
			if (maxInspectedCells < 1) {
				throw new IllegalArgumentException("maxInspectedCells must be positive");
			}
		}

		public static SearchRequest forRemainingAir(Position start, SearchMode mode, int remainingAirTicks) {
			return new SearchRequest(
				start,
				mode,
				maxPathStepsForAir(remainingAirTicks),
				DEFAULT_MAX_CANDIDATES,
				DEFAULT_MAX_INSPECTED_CELLS
			);
		}
	}

	public record Candidate(Position target, SearchMode mode, List<Position> route) {
		public Candidate {
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(mode, "mode");
			Objects.requireNonNull(route, "route");
			route = List.copyOf(route);
			if (route.isEmpty() || !target.equals(route.getLast())) {
				throw new IllegalArgumentException("route must end at target");
			}
		}

		public int pathSteps() {
			return route.size() - 1;
		}
	}

	public record SearchUpdate(
		SearchStatus status,
		List<Candidate> candidates,
		int inspectedCellsThisStep,
		int totalInspectedCells
	) {
		public SearchUpdate {
			Objects.requireNonNull(status, "status");
			candidates = List.copyOf(candidates);
		}
	}

	/**
	 * Incremental pure-computation session. Mutable search collections are fully owned and never exposed.
	 */
	public static final class SearchSession {
		private final SearchRequest request;
		private final CellView world;
		private final ArrayDeque<Position> frontier = new ArrayDeque<>();
		private final Set<Position> discovered = new HashSet<>();
		private final Map<Position, Position> parents = new HashMap<>();
		private final Map<Position, Integer> depths = new HashMap<>();
		private final Set<Position> candidateTargets = new LinkedHashSet<>();
		private final List<Candidate> candidates = new ArrayList<>();
		private final Map<Position, Cell> observedCells = new HashMap<>();
		private Position expandingPosition;
		private int expandingDepth;
		private int nextNeighborIndex;
		private boolean initialized;
		private int totalInspectedCells;
		private SearchStatus status = SearchStatus.SEARCHING;

		private SearchSession(SearchRequest request, CellView world) {
			this.request = Objects.requireNonNull(request, "request");
			this.world = Objects.requireNonNull(world, "world");
		}

		public SearchUpdate advance(int cellInspectionBudget) {
			if (cellInspectionBudget < 1) {
				throw new IllegalArgumentException("cellInspectionBudget must be positive");
			}
			if (status != SearchStatus.SEARCHING) {
				return update(0);
			}

			int inspectedThisStep = 0;
			int allowed = Math.min(cellInspectionBudget, request.maxInspectedCells() - totalInspectedCells);
			while (inspectedThisStep < allowed && status == SearchStatus.SEARCHING) {
				if (!initialized) {
					initialized = true;
					Cell startCell = inspect(request.start());
					inspectedThisStep++;
					totalInspectedCells++;
					if (startCell.matches(request.mode())) {
						addCandidate(request.start(), List.of(request.start()));
					}
					if (startCell.waterRouteCell() && candidates.size() < request.maxCandidates()) {
						frontier.add(request.start());
						discovered.add(request.start());
						depths.put(request.start(), 0);
					}
					else {
						status = SearchStatus.COMPLETE;
					}
					continue;
				}
				if (candidates.size() >= request.maxCandidates()) {
					status = SearchStatus.COMPLETE;
					break;
				}
				if (expandingPosition == null) {
					if (frontier.isEmpty()) {
						status = SearchStatus.COMPLETE;
						break;
					}
					expandingPosition = frontier.removeFirst();
					expandingDepth = depths.get(expandingPosition);
					nextNeighborIndex = 0;
					if (expandingDepth >= request.maxPathSteps()) {
						expandingPosition = null;
						continue;
					}
				}
				if (nextNeighborIndex >= NEIGHBOR_OFFSETS.size()) {
					expandingPosition = null;
					continue;
				}

				Offset offset = NEIGHBOR_OFFSETS.get(nextNeighborIndex++);
				Position neighbor = expandingPosition.offset(offset.x(), offset.y(), offset.z());
				Cell cell = observedCells.get(neighbor);
				if (cell == null) {
					cell = inspect(neighbor);
					inspectedThisStep++;
					totalInspectedCells++;
				}
				int neighborDepth = expandingDepth + 1;
				if (cell.matches(request.mode()) && candidateTargets.add(neighbor)) {
					addCandidate(neighbor, append(reconstruct(expandingPosition), neighbor));
				}
				if (cell.waterRouteCell()
					&& neighborDepth < request.maxPathSteps()
					&& discovered.add(neighbor)) {
					parents.put(neighbor, expandingPosition);
					depths.put(neighbor, neighborDepth);
					frontier.addLast(neighbor);
				}
			}

			if (expandingPosition != null && nextNeighborIndex >= NEIGHBOR_OFFSETS.size()) {
				expandingPosition = null;
			}
			if (candidates.size() >= request.maxCandidates() || frontier.isEmpty()) {
				if (expandingPosition == null || candidates.size() >= request.maxCandidates()) {
					status = SearchStatus.COMPLETE;
				}
			}
			if (status == SearchStatus.SEARCHING && totalInspectedCells >= request.maxInspectedCells()) {
				status = SearchStatus.CELL_BUDGET_EXHAUSTED;
			}
			return update(inspectedThisStep);
		}

		public SearchStatus status() {
			return status;
		}

		public List<Candidate> candidates() {
			return List.copyOf(candidates);
		}

		public int totalInspectedCells() {
			return totalInspectedCells;
		}

		private SearchUpdate update(int inspectedThisStep) {
			return new SearchUpdate(status, candidates, inspectedThisStep, totalInspectedCells);
		}

		private Cell inspect(Position position) {
			Cell observed = Objects.requireNonNullElse(world.cellAt(position), Cell.blocked());
			observedCells.put(position, observed);
			return observed;
		}

		private void addCandidate(Position target, List<Position> route) {
			candidateTargets.add(target);
			candidates.add(new Candidate(target, request.mode(), route));
		}

		private List<Position> reconstruct(Position target) {
			ArrayDeque<Position> reversed = new ArrayDeque<>();
			Position current = target;
			while (current != null) {
				reversed.addFirst(current);
				current = parents.get(current);
			}
			return List.copyOf(reversed);
		}

		private static List<Position> append(List<Position> route, Position target) {
			List<Position> result = new ArrayList<>(route.size() + 1);
			result.addAll(route);
			result.add(target);
			return result;
		}
	}

	private record Offset(int x, int y, int z) {
	}
}
