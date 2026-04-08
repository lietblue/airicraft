package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

final class AskUserStepExecutor implements StepExecutor {
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
		AskUserStepArgs args = requireArgs(step);
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.WAITING,
			null,
			Map.of(),
			Map.of("prompt", args.prompt() == null ? "" : args.prompt()),
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
		if (lastResult.status() == StepExecutionStatus.IDLE) {
			begin(step, evidence, tick);
		}
		AskUserStepArgs args = requireArgs(step);
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.WAITING,
			null,
			Map.of(),
			Map.of("prompt", args.prompt() == null ? "" : args.prompt()),
			tick
		);
		return new StepExecutorTickResult(TaskState.QUEUED, null, new TaskProgressSnapshot(0, 0), TaskStep.ASK_USER, TaskOwnership.NONE, lastResult);
	}

	@Override
	public void cancel(String reason, long tick) {
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return new StepExecutorSnapshot(null, new TaskProgressSnapshot(0, 0), TaskStep.ASK_USER, TaskOwnership.NONE);
	}

	private static AskUserStepArgs requireArgs(LedgerStep step) {
		if (step.args() == null || step.args().askUser() == null) {
			throw new IllegalArgumentException("ASK_USER step requires askUser args");
		}
		return step.args().askUser();
	}
}
