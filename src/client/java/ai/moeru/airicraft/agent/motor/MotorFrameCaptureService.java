package ai.moeru.airicraft.agent.motor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.LongSupplier;

/**
 * Single-flight asynchronous framebuffer capture for the learned motor.
 *
 * <p>{@link #onBeforeHandRender(MinecraftClient)} must be called on Minecraft's
 * render thread after the world has rendered and before the hand/HUD pass. That
 * placement makes {@link ScreenshotRecorder} read the pre-hand framebuffer;
 * this class never changes camera or renderer state.</p>
 */
public final class MotorFrameCaptureService implements MotorFrameSource {
	private final Object lock = new Object();
	private final Executor preprocessingExecutor;
	private final LongSupplier clock;
	private final LongSupplier nanoClock;

	private long lastFrameId;
	private CaptureJob activeJob;

	public MotorFrameCaptureService() {
		this(ForkJoinPool.commonPool(), System::currentTimeMillis, System::nanoTime);
	}

	public MotorFrameCaptureService(Executor preprocessingExecutor, LongSupplier clock) {
		this(preprocessingExecutor, clock, System::nanoTime);
	}

	public MotorFrameCaptureService(
		Executor preprocessingExecutor,
		LongSupplier clock,
		LongSupplier nanoClock
	) {
		this.preprocessingExecutor = Objects.requireNonNull(preprocessingExecutor, "preprocessingExecutor");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
	}

	/** Reserves the next frame id and waits for the next pre-hand render callback. */
	@Override
	public CompletableFuture<MotorFrame> requestCapture() {
		CaptureJob job;
		synchronized (lock) {
			if (activeJob != null) {
				throw new IllegalStateException("A motor frame capture is already in progress");
			}
			if (lastFrameId == Long.MAX_VALUE) {
				throw new IllegalStateException("Motor frame id sequence is exhausted");
			}
			job = new CaptureJob(++lastFrameId);
			activeJob = job;
		}

		job.future.whenComplete((ignored, failure) -> clearIfExternallyCompleted(job));
		return job.future;
	}

	/**
	 * Render-thread callback that consumes at most one pending request.
	 * Calling it with no pending request is a no-op.
	 */
	public void onBeforeHandRender(MinecraftClient client) {
		CaptureJob job;
		synchronized (lock) {
			if (activeJob == null || activeJob.phase != CapturePhase.PENDING) {
				return;
			}
			job = activeJob;
			job.phase = CapturePhase.CAPTURING;
		}

		if (client == null || client.world == null || client.player == null) {
			fail(job, new MotorFrameCaptureException(
				"world_not_loaded",
				"Cannot capture a motor frame without a loaded world"
			));
			return;
		}
		if (client.options == null || client.options.getPerspective() != Perspective.FIRST_PERSON) {
			fail(job, new MotorFrameCaptureException(
				"perspective_not_first_person",
				"Optimus-3 shadow frames must use first-person perspective"
			));
			return;
		}
		synchronized (lock) {
			if (activeJob != job || job.phase != CapturePhase.CAPTURING) {
				return;
			}
			job.captureStartedAtMs = clock.getAsLong();
			job.captureStartedNanos = nanoClock.getAsLong();
		}

		try {
			ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> onNativeImage(job, image));
		}
		catch (Throwable throwable) {
			fail(job, new IllegalStateException("Failed to read the pre-hand framebuffer", throwable));
		}
	}

	/** Fails and releases the active capture, if one exists. */
	public boolean failActiveCapture(Throwable failure) {
		Objects.requireNonNull(failure, "failure");
		CaptureJob job = detachActiveJob();
		if (job == null) {
			return false;
		}
		job.future.completeExceptionally(failure);
		return true;
	}

	/** Cancels and releases the active capture, if one exists. */
	@Override
	public boolean cancelActiveCapture() {
		CaptureJob job = detachActiveJob();
		if (job == null) {
			return false;
		}
		job.future.cancel(false);
		return true;
	}

	@Override
	public MotorFrameCaptureSnapshot snapshot() {
		synchronized (lock) {
			if (activeJob == null) {
				return MotorFrameCaptureSnapshot.idle();
			}
			return new MotorFrameCaptureSnapshot(
				true,
				activeJob.frameId,
				activeJob.phase.name().toLowerCase(Locale.ROOT)
			);
		}
	}

	private void onNativeImage(CaptureJob job, NativeImage image) {
		if (image == null) {
			fail(job, new IllegalStateException("Screenshot recorder returned no image"));
			return;
		}

		BufferedImage source;
		try (image) {
			synchronized (lock) {
				if (activeJob != job || job.phase != CapturePhase.CAPTURING) {
					return;
				}
			}

			int width = image.getWidth();
			int height = image.getHeight();
			if (width <= 0 || height <= 0) {
				throw new IllegalStateException("Screenshot recorder returned invalid dimensions");
			}
			source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			source.setRGB(0, 0, width, height, image.copyPixelsArgb(), 0, width);
		}
		catch (Throwable throwable) {
			fail(job, new IllegalStateException("Failed to copy the captured framebuffer", throwable));
			return;
		}

		synchronized (lock) {
			if (activeJob != job || job.phase != CapturePhase.CAPTURING) {
				return;
			}
			job.phase = CapturePhase.PROCESSING;
		}

		try {
			preprocessingExecutor.execute(() -> preprocess(
				job,
				source,
				job.captureStartedAtMs,
				job.captureStartedNanos
			));
		}
		catch (Throwable throwable) {
			fail(job, new IllegalStateException("Failed to schedule motor frame preprocessing", throwable));
		}
	}

	private void preprocess(
		CaptureJob job,
		BufferedImage source,
		long capturedAtMs,
		long captureStartedNanos
	) {
		try {
			complete(job, MotorFramePreprocessor.preprocess(
				source,
				job.frameId,
				capturedAtMs,
				captureStartedNanos
			));
		}
		catch (Throwable throwable) {
			fail(job, new IllegalStateException("Failed to preprocess motor frame", throwable));
		}
	}

	private void complete(CaptureJob job, MotorFrame frame) {
		synchronized (lock) {
			if (activeJob != job) {
				return;
			}
			activeJob = null;
		}
		job.future.complete(frame);
	}

	private void fail(CaptureJob job, Throwable failure) {
		synchronized (lock) {
			if (activeJob != job) {
				return;
			}
			activeJob = null;
		}
		job.future.completeExceptionally(failure);
	}

	private CaptureJob detachActiveJob() {
		synchronized (lock) {
			CaptureJob job = activeJob;
			activeJob = null;
			return job;
		}
	}

	private void clearIfExternallyCompleted(CaptureJob job) {
		synchronized (lock) {
			if (activeJob == job && job.future.isDone()) {
				activeJob = null;
			}
		}
	}

	private enum CapturePhase {
		PENDING,
		CAPTURING,
		PROCESSING
	}

	private static final class CaptureJob {
		private final long frameId;
		private final CompletableFuture<MotorFrame> future = new CompletableFuture<>();
		private CapturePhase phase = CapturePhase.PENDING;
		private long captureStartedAtMs;
		private long captureStartedNanos;

		private CaptureJob(long frameId) {
			this.frameId = frameId;
		}
	}
}
