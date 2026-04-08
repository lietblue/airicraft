package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

final class FinishStepExecutor implements StepExecutor {
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
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
		FinishStepArgs args = step.args() == null ? null : step.args().finish();
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.COMPLETED,
			null,
			Map.of(),
			args == null || args.reason() == null || args.reason().isBlank()
				? Map.of()
				: Map.of("reason", args.reason()),
			tick
		);
		return new StepExecutorTickResult(
			TaskState.COMPLETED,
			null,
			new TaskProgressSnapshot(0, 0),
			TaskStep.FINISH,
			TaskOwnership.NONE,
			lastResult
		);
	}

	@Override
	public void cancel(String reason, long tick) {
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return new StepExecutorSnapshot(null, new TaskProgressSnapshot(0, 0), TaskStep.FINISH, TaskOwnership.NONE);
	}
}
