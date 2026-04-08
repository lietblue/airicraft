package ai.moeru.airicraft.agent.tasks;

public record TaskSnapshot(
	TaskState state,
	MissionSpec mission,
	TaskLedger ledger,
	TaskSpec spec,
	TaskProgressSnapshot progress,
	TaskStep currentStep,
	TaskOwnership currentGoalOwnership,
	String source,
	String lastFailure,
	String activeStepId,
	LedgerStepKind activeStepKind,
	StepExecutionResult lastStepResult,
	long updatedTick
) {
	public static TaskSnapshot idle() {
		return new TaskSnapshot(
			TaskState.IDLE,
			null,
			null,
			null,
			new TaskProgressSnapshot(0, 0),
			TaskStep.NONE,
			TaskOwnership.NONE,
			null,
			null,
			null,
			null,
			StepExecutionResult.idle(),
			-1L
		);
	}

	public static TaskSnapshot queued(MissionSpec mission, TaskLedger ledger, TaskSpec spec, String source, long tick) {
		return new TaskSnapshot(
			TaskState.QUEUED,
			mission,
			ledger,
			spec,
			TaskProgressSnapshot.of(0, spec.quantity()),
			TaskStep.NONE,
			TaskOwnership.NONE,
			source,
			null,
			ledger == null ? null : ledger.activeStepId(),
			ledger == null ? null : ledger.steps().stream()
				.filter(step -> step.id().equals(ledger.activeStepId()))
				.map(LedgerStep::kind)
				.findFirst()
				.orElse(null),
			StepExecutionResult.idle(),
			tick
		);
	}
}
