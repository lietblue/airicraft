package ai.moeru.airicraft.agent.motor;

public record MotorPolicyServiceTiming(double queueMs, double inferenceMs, double totalMs) {
	public MotorPolicyServiceTiming {
		if (!valid(queueMs) || !valid(inferenceMs) || !valid(totalMs)) {
			throw new IllegalArgumentException("service timing values must be finite and non-negative");
		}
	}

	private static boolean valid(double value) {
		return Double.isFinite(value) && value >= 0.0d;
	}
}
