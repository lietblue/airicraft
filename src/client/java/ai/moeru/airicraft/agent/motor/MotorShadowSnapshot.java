package ai.moeru.airicraft.agent.motor;

/** Metadata-only runtime view. It cannot retain credentials or frame payloads. */
public record MotorShadowSnapshot(
	boolean enabled,
	MotorShadowStatus status,
	long generation,
	MotorGraphIdentity identity,
	String sessionId,
	long nextStepIndex,
	boolean requestInFlight,
	String inFlightOperation,
	MotorFrameSnapshot pendingFrame,
	MotorPolicyStepResult lastResult,
	long observations,
	long sessionCreates,
	long validatedSessions,
	long stepRequests,
	long sessionCloses,
	long completedSteps,
	long backpressuredObservations,
	long droppedObservations,
	long staleResults,
	long failures,
	long timeouts,
	long suppressedAfterFailure,
	String lastFailureCode
) {
	public static MotorShadowSnapshot disabled() {
		return new MotorShadowSnapshot(
			false,
			MotorShadowStatus.DISABLED,
			0,
			null,
			null,
			0,
			false,
			null,
			null,
			null,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			null
		);
	}
}
