package ai.moeru.airicraft.agent.motor;

/** Explicit metadata-only lifecycle transition emitted by the async runtime. */
public record MotorShadowTransition(
	Type type,
	MotorGraphIdentity identity,
	long generation,
	String sessionId,
	int recurrentResetCount,
	MotorPolicyStepResult stepResult,
	String reason,
	boolean successful
) {
	public enum Type {
		SESSION_VALIDATED,
		STEP_COMPLETED,
		FAILURE,
		SESSION_RETIRED,
		SESSION_CLOSED
	}
}
