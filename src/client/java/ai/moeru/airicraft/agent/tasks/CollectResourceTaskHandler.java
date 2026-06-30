package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

public final class CollectResourceTaskHandler {
	public GoalSnapshot start(TaskSpec spec, long tick) {
		return start(spec, spec.quantity(), tick);
	}

	public GoalSnapshot start(TaskSpec spec, int remainingQuantity, long tick) {
		if (spec.type() != TaskType.COLLECT_RESOURCE) {
			throw new IllegalArgumentException("Unsupported task spec: " + spec);
		}
		return start(spec.resourceKind(), remainingQuantity, tick);
	}

	public GoalSnapshot start(TaskResourceKind resourceKind, int remainingQuantity, long tick) {
		java.util.List<String> blockIds = ResourceGatheringCatalog.targetBlockIds(resourceKind);
		if (blockIds.isEmpty()) {
			throw new IllegalArgumentException("Unsupported resource kind: " + resourceKind);
		}
		return new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(blockIds, remainingQuantity),
			tick,
			"task_runtime"
		);
	}

	public static java.util.List<String> targetBlockIds(TaskSpec spec) {
		if (spec == null || spec.type() != TaskType.COLLECT_RESOURCE) {
			return java.util.List.of();
		}
		return ResourceGatheringCatalog.targetBlockIds(spec.resourceKind());
	}

	public static boolean matchesResourceKind(TaskResourceKind resourceKind, java.util.List<String> blockIds) {
		java.util.List<String> resourceBlocks = ResourceGatheringCatalog.targetBlockIds(resourceKind);
		if (resourceBlocks.isEmpty() || blockIds == null || blockIds.isEmpty()) {
			return false;
		}
		return blockIds.stream().allMatch(resourceBlocks::contains);
	}
}
