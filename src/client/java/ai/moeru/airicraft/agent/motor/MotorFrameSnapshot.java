package ai.moeru.airicraft.agent.motor;

/** Metadata-only view of a captured motor frame. It never contains image bytes. */
public record MotorFrameSnapshot(
	long frameId,
	long capturedAtMs,
	String format,
	int width,
	int height,
	int sourceWidth,
	int sourceHeight,
	String transform,
	String encodedSha256,
	String decodedRgbSha256,
	int encodedByteLength
) {
}
