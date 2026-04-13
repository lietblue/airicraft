package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

import java.util.Map;

final class CollectionStepExecutor implements StepExecutor {
	private static final long PICKUP_SETTLE_TICKS = 20L;
	private static final int MAX_STALLED_ATTEMPTS = 2;

	private final CollectResourceTaskHandler collectResourceTaskHandler = new CollectResourceTaskHandler();

	private GoalSnapshot currentGoal;
	private int baselineResourceCount;
	private int collectedAtAttemptStart;
	private int stalledAttemptCount;
	private long waitUntilTick = -1L;
	private boolean started;
	private TaskProgressSnapshot lastProgress = new TaskProgressSnapshot(0, 0);
	private TaskStep lastStep = TaskStep.NONE;
	private TaskOwnership lastOwnership = TaskOwnership.NONE;
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
		CollectResourceStepArgs args = requireArgs(step);
		currentGoal = null;
		baselineResourceCount = evidence.inventoryCounts().getOrDefault(args.resourceKind(), 0);
		collectedAtAttemptStart = 0;
		stalledAttemptCount = 0;
		waitUntilTick = -1L;
		started = true;
		lastProgress = TaskProgressSnapshot.of(0, args.quantity());
		lastStep = TaskStep.NONE;
		lastOwnership = TaskOwnership.NONE;
		lastResult = new StepExecutionResult(step.id(), StepExecutionStatus.IDLE, null, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorTickResult tick(
		LedgerStep step,
		WorldEvidence evidence,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		boolean nearbyStepTargetsAvailable,
		long tick
	) {
		CollectResourceStepArgs args = requireArgs(step);
		if (!started) {
			begin(step, evidence, tick);
		}

		int currentResourceCount = evidence.inventoryCounts().getOrDefault(args.resourceKind(), 0);
		int collected = Math.max(0, currentResourceCount - baselineResourceCount);
		lastProgress = TaskProgressSnapshot.of(collected, args.quantity());

		if (collected >= args.quantity()) {
			currentGoal = null;
			lastStep = TaskStep.NONE;
			lastOwnership = TaskOwnership.NONE;
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.COMPLETED,
				null,
				Map.of("resourceKind", args.resourceKind().name(), "quantity", collected),
				Map.of("reason", "inventory_delta_satisfied"),
				tick
			);
			return new StepExecutorTickResult(TaskState.COMPLETED, null, lastProgress, lastStep, lastOwnership, lastResult);
		}

		if (waitUntilTick >= 0L) {
			if (tick < waitUntilTick) {
				lastStep = TaskStep.WAIT_FOR_PICKUP;
				lastOwnership = TaskOwnership.NONE;
				lastResult = new StepExecutionResult(step.id(), StepExecutionStatus.WAITING, null, Map.of(), Map.of(), tick);
				return new StepExecutorTickResult(TaskState.WAITING_FOR_PICKUP, null, lastProgress, lastStep, lastOwnership, lastResult);
			}
			boolean madeProgress = collected > collectedAtAttemptStart;
			stalledAttemptCount = madeProgress ? 0 : stalledAttemptCount + 1;
			waitUntilTick = -1L;
			if (!nearbyStepTargetsAvailable) {
				return fail(step.id(), lastProgress, "no_nearby_resource_targets", tick);
			}
			if (stalledAttemptCount > MAX_STALLED_ATTEMPTS) {
				return fail(step.id(), lastProgress, "resource_collection_stalled", tick);
			}
			startMineAttempt(step.id(), args, collected, tick);
			return running(step.id(), tick);
		}

		if (!actuationAllowed) {
			lastResult = new StepExecutionResult(step.id(), StepExecutionStatus.WAITING, null, Map.of(), Map.of("reason", "session_gate_paused"), tick);
			return new StepExecutorTickResult(TaskState.PAUSED_BY_SESSION_GATE, currentGoal, lastProgress, lastStep, lastOwnership, lastResult);
		}

		if (currentGoal == null) {
			startMineAttempt(step.id(), args, collected, tick);
			return running(step.id(), tick);
		}

		if (
			primitiveExecution != null
				&& (primitiveExecution.state() == TaskExecutionState.COMPLETED
				|| primitiveExecution.state() == TaskExecutionState.CANCELLED
				|| primitiveExecution.state() == TaskExecutionState.FAILED)
		) {
			currentGoal = null;
			waitUntilTick = tick + PICKUP_SETTLE_TICKS;
			lastStep = TaskStep.WAIT_FOR_PICKUP;
			lastOwnership = TaskOwnership.NONE;
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.WAITING,
				null,
				Map.of(),
				Map.of("primitiveState", primitiveExecution.state().name()),
				tick
			);
			return new StepExecutorTickResult(TaskState.WAITING_FOR_PICKUP, null, lastProgress, lastStep, lastOwnership, lastResult);
		}

		return running(step.id(), tick);
	}

	@Override
	public void cancel(String reason, long tick) {
		currentGoal = null;
		waitUntilTick = -1L;
		lastStep = TaskStep.NONE;
		lastOwnership = TaskOwnership.NONE;
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return new StepExecutorSnapshot(currentGoal, lastProgress, lastStep, lastOwnership);
	}

	private StepExecutorTickResult running(String stepId, long tick) {
		lastStep = TaskStep.MINE_TARGET;
		lastOwnership = currentGoal == null ? TaskOwnership.NONE : TaskOwnership.TASK_RUNTIME;
		lastResult = new StepExecutionResult(stepId, StepExecutionStatus.RUNNING, null, Map.of(), Map.of(), tick);
		return new StepExecutorTickResult(TaskState.RUNNING, currentGoal, lastProgress, lastStep, lastOwnership, lastResult);
	}

	private StepExecutorTickResult fail(String stepId, TaskProgressSnapshot progress, String reason, long tick) {
		currentGoal = null;
		lastStep = TaskStep.NONE;
		lastOwnership = TaskOwnership.NONE;
		lastResult = new StepExecutionResult(stepId, StepExecutionStatus.FAILED, reason, Map.of(), Map.of(), tick);
		return new StepExecutorTickResult(TaskState.FAILED, null, progress, lastStep, lastOwnership, lastResult);
	}

	private void startMineAttempt(String stepId, CollectResourceStepArgs args, int collected, long tick) {
		int desiredInventoryCount = Math.max(1, baselineResourceCount + args.quantity());
		currentGoal = collectResourceTaskHandler.start(args.resourceKind(), desiredInventoryCount, tick);
		collectedAtAttemptStart = collected;
		lastStep = TaskStep.MINE_TARGET;
		lastOwnership = TaskOwnership.TASK_RUNTIME;
		lastResult = new StepExecutionResult(stepId, StepExecutionStatus.RUNNING, null, Map.of(), Map.of("desiredInventoryCount", desiredInventoryCount), tick);
	}

	private static CollectResourceStepArgs requireArgs(LedgerStep step) {
		if (step.args() == null || step.args().collectResource() == null) {
			throw new IllegalArgumentException("COLLECT_RESOURCE step requires collectResource args");
		}
		return step.args().collectResource();
	}
}
