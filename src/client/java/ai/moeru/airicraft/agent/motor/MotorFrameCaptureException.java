package ai.moeru.airicraft.agent.motor;

/** Stable, evidence-safe reason for rejecting a policy frame before inference. */
public final class MotorFrameCaptureException extends RuntimeException {
	private final String code;

	public MotorFrameCaptureException(String code, String message) {
		super(message);
		this.code = code == null || code.isBlank() ? "capture_failed" : code;
	}

	public String code() {
		return code;
	}
}
