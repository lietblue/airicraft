package ai.moeru.airicraft.agent.motor;

/** Sanitized policy transport/protocol failure; never includes credentials or image bytes. */
public final class MotorPolicyException extends RuntimeException {
	private final String code;

	public MotorPolicyException(String code, String message) {
		this(code, message, null);
	}

	public MotorPolicyException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}

	static MotorPolicyException schema(String message) {
		return new MotorPolicyException("invalid_response_schema", message);
	}
}
