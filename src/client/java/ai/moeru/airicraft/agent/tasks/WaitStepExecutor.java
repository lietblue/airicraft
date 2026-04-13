package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

final class WaitStepExecutor implements StepExecutor {
	private long resumeTick = -1L;
	private TaskProgressSnapshot lastProgress = new TaskProgressSnapshot(0, 0);
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
		WaitStepArgs args = requireArgs(step);
		resumeTick = tick + args.ticks();
		lastProgress = TaskProgressSnapshot.of(0, (int) Math.min(Integer.MAX_VALUE, args.ticks()));
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.WAITING,
			null,
			Map.of(),
			Map.of("resumeTick", resumeTick, "reason", args.reason() == null ? "" : args.reason()),
			tick
		);
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
		if (resumeTick < 0L) {
			begin(step, evidence, tick);
		}
		WaitStepArgs args = requireArgs(step);
		int totalTicks = (int) Math.min(Integer.MAX_VALUE, args.ticks());
		int elapsed = Math.max(0, totalTicks - (int) Math.max(0L, resumeTick - tick));
		lastProgress = TaskProgressSnapshot.of(elapsed, totalTicks);
		if (tick >= resumeTick) {
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.COMPLETED,
				null,
				Map.of("waitedTicks", args.ticks()),
				Map.of("reason", args.reason() == null ? "" : args.reason()),
				tick
			);
			return new StepExecutorTickResult(TaskState.COMPLETED, null, lastProgress, TaskStep.WAIT, TaskOwnership.NONE, lastResult);
		}
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.WAITING,
			null,
			Map.of("remainingTicks", Math.max(0L, resumeTick - tick)),
			Map.of("reason", args.reason() == null ? "" : args.reason()),
			tick
		);
		return new StepExecutorTickResult(TaskState.RUNNING, null, lastProgress, TaskStep.WAIT, TaskOwnership.NONE, lastResult);
	}

	@Override
	public void cancel(String reason, long tick) {
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
		resumeTick = -1L;
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return new StepExecutorSnapshot(null, lastProgress, TaskStep.WAIT, TaskOwnership.NONE);
	}

	private static WaitStepArgs requireArgs(LedgerStep step) {
		if (step.args() == null || step.args().waitStep() == null) {
			throw new IllegalArgumentException("WAIT step requires waitStep args");
		}
		return step.args().waitStep();
	}
}
