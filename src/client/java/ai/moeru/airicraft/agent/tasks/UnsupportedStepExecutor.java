package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

final class UnsupportedStepExecutor implements StepExecutor {
	private final LedgerStepKind kind;
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	UnsupportedStepExecutor(LedgerStepKind kind) {
		this.kind = kind;
	}

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.FAILED,
			"unsupported_step_kind:" + kind.name(),
			Map.of(),
			Map.of("kind", kind.name()),
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
		begin(step, evidence, tick);
		return new StepExecutorTickResult(TaskState.FAILED, null, new TaskProgressSnapshot(0, 0), TaskStep.NONE, TaskOwnership.NONE, lastResult);
	}

	@Override
	public void cancel(String reason, long tick) {
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return StepExecutorSnapshot.idle();
	}
}
