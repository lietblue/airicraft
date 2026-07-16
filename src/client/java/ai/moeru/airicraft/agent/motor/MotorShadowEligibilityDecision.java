package ai.moeru.airicraft.agent.motor;

public record MotorShadowEligibilityDecision(
	boolean eligible,
	String reason,
	MotorGraphIdentity identity
) {
	public MotorShadowEligibilityDecision {
		reason = reason == null || reason.isBlank() ? "unknown" : reason;
		if (eligible && identity == null) {
			throw new IllegalArgumentException("eligible decisions require a graph identity");
		}
		if (!eligible && identity != null) {
			throw new IllegalArgumentException("ineligible decisions must not expose a graph identity");
		}
	}

	public static MotorShadowEligibilityDecision eligible(MotorGraphIdentity identity) {
		return new MotorShadowEligibilityDecision(true, "eligible", identity);
	}

	public static MotorShadowEligibilityDecision ineligible(String reason) {
		return new MotorShadowEligibilityDecision(false, reason, null);
	}
}
