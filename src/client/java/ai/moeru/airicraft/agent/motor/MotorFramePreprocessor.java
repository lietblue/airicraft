package ai.moeru.airicraft.agent.motor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Pure image preprocessing for the Stage 4 Optimus-3 shadow adapter. */
public final class MotorFramePreprocessor {
	private static final int INTERPOLATION_BITS = 11;
	private static final int INTERPOLATION_SCALE = 1 << INTERPOLATION_BITS;
	private static final long INTERPOLATION_ROUNDING = 1L << ((INTERPOLATION_BITS * 2) - 1);

	public static final String FORMAT = "png";
	public static final int TARGET_WIDTH = 128;
	public static final int TARGET_HEIGHT = 128;
	public static final String TRANSFORM = "full_frame_bilinear_resize_rgb_v1";

	private MotorFramePreprocessor() {
	}

	/**
	 * Converts a framebuffer image into the policy's exact {@code 128x128x3}
	 * input geometry.
	 *
	 * <p>This intentionally performs one full-frame bilinear resize, matching
	 * the released Optimus/VPT adapter. It does not crop, preserve the source
	 * aspect ratio, or add letterbox bars. This establishes a deterministic live
	 * adapter; it does not claim that Fabric's framebuffer is distribution-identical
	 * to MineStudio's native observation. Pixel centers use the OpenCV
	 * resize mapping {@code (destination + 0.5) * scale - 0.5}; 11-bit fixed
	 * point weights make the result independent of Java2D/platform rendering.
	 * The destination is explicitly RGB (alpha is composited over black), then
	 * encoded as PNG. The decoded pixel digest covers row-major {@code R,G,B}
	 * bytes and is therefore independent of PNG compression details.</p>
	 */
	public static MotorFrame preprocess(BufferedImage source, long frameId, long capturedAtMs) {
		return preprocess(source, frameId, capturedAtMs, System.nanoTime());
	}

	public static MotorFrame preprocess(
		BufferedImage source,
		long frameId,
		long capturedAtMs,
		long captureStartedNanos
	) {
		Objects.requireNonNull(source, "source");
		if (source.getWidth() <= 0 || source.getHeight() <= 0) {
			throw new IllegalArgumentException("source image must have positive dimensions");
		}

		BufferedImage resized = resizeRgb(source);
		byte[] pngBytes = encodePng(resized);
		byte[] rgbBytes = decodedRgbBytes(resized);
		return new MotorFrame(
			frameId,
			capturedAtMs,
			captureStartedNanos,
			FORMAT,
			TARGET_WIDTH,
			TARGET_HEIGHT,
			source.getWidth(),
			source.getHeight(),
			TRANSFORM,
			sha256(pngBytes),
			sha256(rgbBytes),
			pngBytes
		);
	}

	private static BufferedImage resizeRgb(BufferedImage source) {
		BufferedImage target = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
		if (source.getWidth() == TARGET_WIDTH && source.getHeight() == TARGET_HEIGHT) {
			for (int y = 0; y < TARGET_HEIGHT; y++) {
				for (int x = 0; x < TARGET_WIDTH; x++) {
					target.setRGB(x, y, compositeOverBlack(source.getRGB(x, y)));
				}
			}
			return target;
		}

		AxisSample[] horizontal = samples(source.getWidth(), TARGET_WIDTH);
		AxisSample[] vertical = samples(source.getHeight(), TARGET_HEIGHT);
		for (int y = 0; y < TARGET_HEIGHT; y++) {
			AxisSample sy = vertical[y];
			for (int x = 0; x < TARGET_WIDTH; x++) {
				AxisSample sx = horizontal[x];
				int topLeft = compositeOverBlack(source.getRGB(sx.low, sy.low));
				int topRight = compositeOverBlack(source.getRGB(sx.high, sy.low));
				int bottomLeft = compositeOverBlack(source.getRGB(sx.low, sy.high));
				int bottomRight = compositeOverBlack(source.getRGB(sx.high, sy.high));
				target.setRGB(x, y, interpolateRgb(topLeft, topRight, bottomLeft, bottomRight, sx, sy));
			}
		}
		return target;
	}

	private static AxisSample[] samples(int sourceSize, int targetSize) {
		AxisSample[] samples = new AxisSample[targetSize];
		double scale = (double) sourceSize / targetSize;
		for (int destination = 0; destination < targetSize; destination++) {
			double sourceCoordinate = ((destination + 0.5d) * scale) - 0.5d;
			int low = (int) Math.floor(sourceCoordinate);
			double fraction = sourceCoordinate - low;
			if (low < 0) {
				low = 0;
				fraction = 0.0d;
			}
			else if (low >= sourceSize - 1) {
				low = sourceSize - 1;
				fraction = 0.0d;
			}
			int high = Math.min(sourceSize - 1, low + 1);
			int highWeight = (int) Math.round(fraction * INTERPOLATION_SCALE);
			samples[destination] = new AxisSample(low, high, INTERPOLATION_SCALE - highWeight, highWeight);
		}
		return samples;
	}

	private static int interpolateRgb(
		int topLeft,
		int topRight,
		int bottomLeft,
		int bottomRight,
		AxisSample horizontal,
		AxisSample vertical
	) {
		int red = interpolateChannel(topLeft >>> 16, topRight >>> 16, bottomLeft >>> 16, bottomRight >>> 16, horizontal, vertical);
		int green = interpolateChannel(topLeft >>> 8, topRight >>> 8, bottomLeft >>> 8, bottomRight >>> 8, horizontal, vertical);
		int blue = interpolateChannel(topLeft, topRight, bottomLeft, bottomRight, horizontal, vertical);
		return 0xff000000 | (red << 16) | (green << 8) | blue;
	}

	private static int interpolateChannel(
		int topLeft,
		int topRight,
		int bottomLeft,
		int bottomRight,
		AxisSample horizontal,
		AxisSample vertical
	) {
		long top = ((long) (topLeft & 0xff) * horizontal.lowWeight)
			+ ((long) (topRight & 0xff) * horizontal.highWeight);
		long bottom = ((long) (bottomLeft & 0xff) * horizontal.lowWeight)
			+ ((long) (bottomRight & 0xff) * horizontal.highWeight);
		return (int) (((top * vertical.lowWeight) + (bottom * vertical.highWeight) + INTERPOLATION_ROUNDING)
			>> (INTERPOLATION_BITS * 2));
	}

	private static int compositeOverBlack(int argb) {
		int alpha = (argb >>> 24) & 0xff;
		if (alpha == 0xff) {
			return 0xff000000 | (argb & 0x00ffffff);
		}
		int red = ((((argb >>> 16) & 0xff) * alpha) + 127) / 255;
		int green = ((((argb >>> 8) & 0xff) * alpha) + 127) / 255;
		int blue = (((argb & 0xff) * alpha) + 127) / 255;
		return 0xff000000 | (red << 16) | (green << 8) | blue;
	}

	private static byte[] encodePng(BufferedImage image) {
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, FORMAT, output)) {
				throw new IOException("No PNG writer is available");
			}
			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to encode motor frame as PNG", exception);
		}
	}

	private static byte[] decodedRgbBytes(BufferedImage image) {
		byte[] rgb = new byte[image.getWidth() * image.getHeight() * 3];
		int offset = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int pixel = image.getRGB(x, y);
				rgb[offset++] = (byte) ((pixel >>> 16) & 0xff);
				rgb[offset++] = (byte) ((pixel >>> 8) & 0xff);
				rgb[offset++] = (byte) (pixel & 0xff);
			}
		}
		return rgb;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("JVM does not provide SHA-256", exception);
		}
	}

	private record AxisSample(int low, int high, int lowWeight, int highWeight) {
	}
}
