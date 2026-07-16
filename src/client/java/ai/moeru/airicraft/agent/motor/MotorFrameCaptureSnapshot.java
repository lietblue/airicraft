package ai.moeru.airicraft.agent.motor;

/** Metadata-only state for the single-flight capture service. */
public record MotorFrameCaptureSnapshot(
	boolean active,
	long frameId,
	String phase
) {
	public static MotorFrameCaptureSnapshot idle() {
		return new MotorFrameCaptureSnapshot(false, 0L, "idle");
	}
}
