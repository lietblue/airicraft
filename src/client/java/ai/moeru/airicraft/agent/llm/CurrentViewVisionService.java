package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.minecraft.client.MinecraftClient;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CurrentViewVisionService implements CurrentViewVisionTool {
	public static final String DEFAULT_DESCRIBE_PROMPT =
		"Describe the current Minecraft first-person view in one short paragraph. " +
			"Mention terrain, nearby landmarks, hazards, structures, and whether the scene feels indoors or outdoors.";

	private final FirstPersonScreenshotService screenshotService;
	private final VisionBackend visionBackend;
	private final Supplier<MinecraftClient> clientSupplier;
	private final ExecutorService executorService;
	private final AgentObservability observability;

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<MinecraftClient> clientSupplier
	) {
		this(screenshotService, visionBackend, clientSupplier, NoopObservability.INSTANCE);
	}

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<MinecraftClient> clientSupplier,
		AgentObservability observability
	) {
		this.screenshotService = Objects.requireNonNull(screenshotService, "screenshotService");
		this.visionBackend = Objects.requireNonNull(visionBackend, "visionBackend");
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-vision");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public boolean isConfigured() {
		return visionBackend.isConfigured();
	}

	@Override
	public CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.failedFuture(
				new BridgeUnavailableException("world_not_loaded", "No world is currently loaded")
			);
		}

		try {
			Context captureContext = observability.startChildSpan(
				AgentObservability.TOOL_CAPTURE_SPAN_NAME,
				Context.current()
			);
			return screenshotService.requestCapture(client).whenComplete((capture, throwable) -> {
				try {
					if (capture != null) {
						observability.recordImageCapture(captureContext, capture);
					}
					else if (throwable != null) {
						observability.recordFailure(
							captureContext,
							LlmFailureType.PROVIDER_ERROR.name(),
							"Vision capture failed",
							Throwable.class.isAssignableFrom(throwable.getClass()) ? throwable : new RuntimeException(throwable)
						);
					}
				}
				finally {
					observability.endSpan(captureContext);
				}
			});
		}
		catch (RuntimeException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_ERROR.name(), "Vision capture failed", exception);
			return CompletableFuture.failedFuture(exception);
		}
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) {
		if (!isConfigured()) {
			return CompletableFuture.failedFuture(
				new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Vision provider is not configured")
			);
		}

		try {
			Context parentContext = Context.current();
			return CompletableFuture.supplyAsync(() -> {
				Context describeContext = observability.startChildSpan(
					AgentObservability.VISION_DESCRIBE_SPAN_NAME,
					parentContext
				);
				try (Scope scope = describeContext.makeCurrent()) {
					return describeWithinCurrentSpan(screenshot, prompt);
				}
				catch (LlmBackendException exception) {
					throw new CompletionException(exception);
				}
				finally {
					observability.endSpan(describeContext);
				}
			}, executorService);
		}
		catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(String prompt) {
		return CurrentViewVisionTool.super.requestDescription(prompt);
	}

	public VisionDescription describe(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Context describeContext = observability.startChildSpan(
			AgentObservability.VISION_DESCRIBE_SPAN_NAME,
			Context.current()
		);
		try (Scope scope = describeContext.makeCurrent()) {
			return describeWithinCurrentSpan(screenshot, prompt);
		}
		finally {
			observability.endSpan(describeContext);
		}
	}

	private VisionDescription describeWithinCurrentSpan(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Objects.requireNonNull(screenshot, "screenshot");
		return visionBackend.describe(new VisionRequest(
			normalizePrompt(prompt),
			"image/png",
			screenshot.imageBytes(),
			screenshot.capturedAtMs()
		));
	}

	public void shutdown() {
		executorService.shutdownNow();
	}

	private static String normalizePrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return DEFAULT_DESCRIBE_PROMPT;
		}
		return prompt;
	}
}
