package ai.moeru.airicraft.agent.tasks;

public interface StepExecutor {
	void begin(LedgerStep step, WorldEvidence evidence, long tick);

	StepExecutorTickResult tick(
		LedgerStep step,
		WorldEvidence evidence,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		boolean nearbyStepTargetsAvailable,
		long tick
	);

	void cancel(String reason, long tick);

	StepExecutorSnapshot snapshot();
}
