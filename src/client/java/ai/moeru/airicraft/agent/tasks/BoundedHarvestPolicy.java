package ai.moeru.airicraft.agent.tasks;

import java.util.Comparator;
import java.util.List;

/**
 * Pure policy for bounded, environment-aware block harvesting.
 */
public final class BoundedHarvestPolicy {
	public static final int HORIZONTAL_RADIUS = 20;
	public static final int VERTICAL_RADIUS = 12;
	public static final int TARGET_BATCH_SIZE = 4;
	public static final int AIR_RESERVE_TICKS = 180;
	public static final int AIR_RESUME_MARGIN_TICKS = 20;

	private BoundedHarvestPolicy() {
	}

	public static SourceEnvironment classify(boolean sourceContainsFluid, boolean adjacentFluid) {
		if (sourceContainsFluid) {
			return SourceEnvironment.FLUID_CONTAINED;
		}
		return adjacentFluid ? SourceEnvironment.SUBMERGED : SourceEnvironment.DRY;
	}

	public static boolean shouldSurface(boolean submerged, int remainingAir, int maxAir) {
		return submerged && remainingAir <= Math.min(AIR_RESERVE_TICKS, Math.max(1, maxAir - AIR_RESUME_MARGIN_TICKS));
	}

	public static boolean mayResumeHarvest(boolean submerged, int remainingAir, int maxAir) {
		return !submerged && remainingAir >= Math.max(0, maxAir - AIR_RESUME_MARGIN_TICKS);
	}

	public static List<Target> selectBatch(List<Target> candidates, Position origin) {
		if (candidates == null || candidates.isEmpty() || origin == null) {
			return List.of();
		}
		return candidates.stream()
			.filter(target -> target != null && inBounds(target.position(), origin))
			.sorted(Comparator
				.comparingDouble((Target target) -> squaredDistance(target.position(), origin))
				.thenComparingInt(target -> target.position().y())
				.thenComparingInt(target -> target.position().x())
				.thenComparingInt(target -> target.position().z()))
			.limit(TARGET_BATCH_SIZE)
			.toList();
	}

	public static TerminalDecision terminalDecision(int inventoryCount, int targetCount, int remainingTargets) {
		if (inventoryCount >= targetCount) {
			return TerminalDecision.COMPLETE;
		}
		return remainingTargets <= 0 ? TerminalDecision.RESOURCE_NOT_FOUND_NEARBY : TerminalDecision.CONTINUE;
	}

	private static boolean inBounds(Position target, Position origin) {
		return Math.abs(target.x() - origin.x()) <= HORIZONTAL_RADIUS
			&& Math.abs(target.z() - origin.z()) <= HORIZONTAL_RADIUS
			&& Math.abs(target.y() - origin.y()) <= VERTICAL_RADIUS;
	}

	private static double squaredDistance(Position left, Position right) {
		double dx = left.x() - right.x();
		double dy = left.y() - right.y();
		double dz = left.z() - right.z();
		return dx * dx + dy * dy + dz * dz;
	}

	public enum SourceEnvironment {
		DRY,
		FLUID_CONTAINED,
		SUBMERGED;

		public boolean underwater() {
			return this != DRY;
		}
	}

	public enum TerminalDecision {
		CONTINUE,
		COMPLETE,
		RESOURCE_NOT_FOUND_NEARBY
	}

	public record Position(int x, int y, int z) {
	}

	public record Target(Position position, String blockId, SourceEnvironment environment) {
	}
}
