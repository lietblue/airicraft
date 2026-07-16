package ai.moeru.airicraft.agent.motor;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorFramePreprocessorTest {
	@Test
	void producesExactRgbPngWithVerifiableStableHashes() throws Exception {
		BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
		source.setRGB(0, 0, new Color(255, 0, 0).getRGB());
		source.setRGB(1, 0, new Color(0, 255, 0).getRGB());
		source.setRGB(2, 0, new Color(0, 0, 255).getRGB());
		source.setRGB(0, 1, new Color(255, 255, 0).getRGB());
		source.setRGB(1, 1, new Color(0, 255, 255).getRGB());
		source.setRGB(2, 1, new Color(255, 0, 255).getRGB());

		MotorFrame first = MotorFramePreprocessor.preprocess(source, 7L, 1234L);
		MotorFrame second = MotorFramePreprocessor.preprocess(source, 8L, 1235L);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(first.pngBytes()));

		assertEquals(128, first.width());
		assertEquals(128, first.height());
		assertEquals(3, first.sourceWidth());
		assertEquals(2, first.sourceHeight());
		assertEquals(128, decoded.getWidth());
		assertEquals(128, decoded.getHeight());
		assertFalse(decoded.getColorModel().hasAlpha());
		assertEquals(sha256(first.pngBytes()), first.encodedSha256());
		assertEquals(sha256(rgbBytes(decoded)), first.decodedRgbSha256());
		assertEquals(first.encodedSha256(), second.encodedSha256());
		assertEquals(first.decodedRgbSha256(), second.decodedRgbSha256());
		assertEquals(MotorFramePreprocessor.TRANSFORM, first.transform());
	}

	@Test
	void resizesTheWholeFrameWithoutCroppingOrLetterboxing() throws Exception {
		BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < source.getHeight(); y++) {
			source.setRGB(0, y, Color.RED.getRGB());
			source.setRGB(1, y, Color.GREEN.getRGB());
			source.setRGB(2, y, Color.GREEN.getRGB());
			source.setRGB(3, y, Color.BLUE.getRGB());
		}

		MotorFrame frame = MotorFramePreprocessor.preprocess(source, 1L, 1L);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frame.pngBytes()));

		assertEquals(Color.RED.getRGB(), decoded.getRGB(0, 64));
		assertEquals(Color.BLUE.getRGB(), decoded.getRGB(127, 64));
		assertNotEquals(Color.BLACK.getRGB(), decoded.getRGB(64, 0));
		assertNotEquals(Color.BLACK.getRGB(), decoded.getRGB(64, 127));
	}

	@Test
	void defensivelyCopiesPayloadAndRedactsItFromStatusViews() {
		BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		source.setRGB(0, 0, new Color(12, 34, 56).getRGB());
		MotorFrame frame = MotorFramePreprocessor.preprocess(source, 1L, 2L);
		byte[] original = frame.pngBytes();
		byte[] mutated = frame.pngBytes();
		mutated[0] ^= 0xff;

		assertArrayEquals(original, frame.pngBytes());
		assertFalse(frame.toString().contains("pngBytes"));
		assertFalse(frame.snapshot().toString().contains(Arrays.toString(original)));
		assertEquals(original.length, frame.snapshot().encodedByteLength());
		assertTrue(frame.toString().contains(frame.encodedSha256()));
	}

	private static byte[] rgbBytes(BufferedImage image) {
		byte[] bytes = new byte[image.getWidth() * image.getHeight() * 3];
		int offset = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				bytes[offset++] = (byte) (rgb >>> 16);
				bytes[offset++] = (byte) (rgb >>> 8);
				bytes[offset++] = (byte) rgb;
			}
		}
		return bytes;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
