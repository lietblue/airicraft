package ai.moeru.airicraft.agent.motor;

import java.util.Objects;

/**
 * One policy-ready Optimus-3 observation.
 *
 * <p>The PNG payload is deliberately excluded from {@link #snapshot()} and
 * {@link #toString()} so routine status/debug recording cannot accidentally
 * retain gameplay images.</p>
 */
public record MotorFrame(
	long frameId,
	long capturedAtMs,
	long captureStartedNanos,
	String format,
	int width,
	int height,
	int sourceWidth,
	int sourceHeight,
	String transform,
	String encodedSha256,
	String decodedRgbSha256,
	byte[] pngBytes
) {
	public MotorFrame {
		if (frameId <= 0L) {
			throw new IllegalArgumentException("frameId must be positive");
		}
		if (capturedAtMs < 0L) {
			throw new IllegalArgumentException("capturedAtMs must not be negative");
		}
		if (!MotorFramePreprocessor.FORMAT.equals(format)) {
			throw new IllegalArgumentException("format must be " + MotorFramePreprocessor.FORMAT);
		}
		if (width != MotorFramePreprocessor.TARGET_WIDTH || height != MotorFramePreprocessor.TARGET_HEIGHT) {
			throw new IllegalArgumentException("motor frames must be exactly 128x128");
		}
		if (sourceWidth <= 0 || sourceHeight <= 0) {
			throw new IllegalArgumentException("source dimensions must be positive");
		}
		transform = requireNonBlank(transform, "transform");
		encodedSha256 = requireSha256(encodedSha256, "encodedSha256");
		decodedRgbSha256 = requireSha256(decodedRgbSha256, "decodedRgbSha256");
		pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
		if (pngBytes.length == 0) {
			throw new IllegalArgumentException("pngBytes must not be empty");
		}
	}

	@Override
	public byte[] pngBytes() {
		return pngBytes.clone();
	}

	public MotorFrameSnapshot snapshot() {
		return new MotorFrameSnapshot(
			frameId,
			capturedAtMs,
			format,
			width,
			height,
			sourceWidth,
			sourceHeight,
			transform,
			encodedSha256,
			decodedRgbSha256,
			pngBytes.length
		);
	}

	@Override
	public String toString() {
		return "MotorFrame[" + snapshot() + "]";
	}

	private static String requireNonBlank(String value, String name) {
		String normalized = Objects.requireNonNull(value, name).trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return normalized;
	}

	private static String requireSha256(String value, String name) {
		String normalized = requireNonBlank(value, name);
		if (!normalized.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex digest");
		}
		return normalized;
	}
}
