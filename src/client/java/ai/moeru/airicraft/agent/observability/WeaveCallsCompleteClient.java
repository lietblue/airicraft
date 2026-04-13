package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class WeaveCallsCompleteClient {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String WANDB_ENTITY_ATTRIBUTE = "wandb.entity";
	private static final String WANDB_PROJECT_ATTRIBUTE = "wandb.project";
	private static final String WANDB_API_KEY_HEADER = "wandb-api-key";
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

	private final HttpClient httpClient;
	private final URI completeEndpoint;
	private final String authorizationHeader;
	private final String projectId;
	private final boolean debugLogExports;

	private WeaveCallsCompleteClient(
		HttpClient httpClient,
		URI completeEndpoint,
		String authorizationHeader,
		String projectId,
		boolean debugLogExports
	) {
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.completeEndpoint = Objects.requireNonNull(completeEndpoint, "completeEndpoint");
		this.authorizationHeader = Objects.requireNonNull(authorizationHeader, "authorizationHeader");
		this.projectId = Objects.requireNonNull(projectId, "projectId");
		this.debugLogExports = debugLogExports;
	}

	static WeaveCallsCompleteClient create(AgentConfig.ObservabilityConfig config) {
		if (config == null || !"weave".equalsIgnoreCase(config.vendorProfile())) {
			return null;
		}
		String entity = config.resourceAttributes().getOrDefault(WANDB_ENTITY_ATTRIBUTE, "");
		String project = config.resourceAttributes().getOrDefault(WANDB_PROJECT_ATTRIBUTE, "");
		if (entity.isBlank() || project.isBlank()) {
			return null;
		}
		String authorization = authorizationHeader(config)
			.orElse("");
		if (authorization.isBlank()) {
			Airicraft.LOGGER.warn("Weave capture sidecar disabled because no OTLP authorization header could be derived");
			return null;
		}
		URI completeEndpoint = completeEndpoint(config.otlpEndpoint(), entity, project)
			.orElse(null);
		if (completeEndpoint == null) {
			Airicraft.LOGGER.warn("Weave capture sidecar disabled because otlpEndpoint {} is invalid", config.otlpEndpoint());
			return null;
		}
		return new WeaveCallsCompleteClient(
			HttpClient.newBuilder()
				.connectTimeout(REQUEST_TIMEOUT)
				.build(),
			completeEndpoint,
			authorization,
			entity + "/" + project,
			config.debugLogExports()
		);
	}

	boolean exportImageCapture(String threadId, FirstPersonScreenshotService.CapturedScreenshot capture) {
		if (capture == null) {
			return false;
		}
		String imageDataUrl = TraceSanitizer.imageCaptureDisplayDataUrl(capture);
		if (imageDataUrl.length() < 1_024) {
			imageDataUrl = TraceSanitizer.imageCaptureDataUrl(capture);
		}
		if (imageDataUrl.isBlank()) {
			return false;
		}
		String requestBody = capturePayload(threadId, capture, imageDataUrl);
		HttpRequest request = HttpRequest.newBuilder(completeEndpoint)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.header(AUTHORIZATION_HEADER, authorizationHeader)
			.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
			if (success) {
				if (debugLogExports) {
					Airicraft.LOGGER.info(
						"Weave capture sidecar export succeeded endpoint={} threadId={} status={}",
						completeEndpoint,
						threadId,
						response.statusCode()
					);
				}
				return true;
			}
			Airicraft.LOGGER.warn(
				"Weave capture sidecar export failed endpoint={} status={} body={}",
				completeEndpoint,
				response.statusCode(),
				TraceSanitizer.summarizeForLog(response.body())
			);
			return false;
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			Airicraft.LOGGER.warn("Weave capture sidecar export failed", exception);
			return false;
		}
	}

	private String capturePayload(String threadId, FirstPersonScreenshotService.CapturedScreenshot capture, String imageDataUrl) {
		JsonObject call = new JsonObject();
		call.addProperty("project_id", projectId);
		call.addProperty("id", UUID.randomUUID().toString());
		call.addProperty("trace_id", UUID.randomUUID().toString());
		call.addProperty("op_name", AgentObservability.TOOL_CAPTURE_SPAN_NAME);
		call.addProperty("display_name", AgentObservability.TOOL_CAPTURE_SPAN_NAME);
		Instant startedAt = captureInstant(capture);
		call.addProperty("started_at", startedAt.toString());
		call.addProperty("ended_at", startedAt.plusMillis(1).toString());
		call.add("inputs", JsonParser.parseString(TraceSanitizer.imageCapturePayloadForTrace(capture)).getAsJsonObject());
		call.addProperty("output", imageDataUrl);
		call.add("attributes", captureAttributes(threadId, capture));
		call.add("summary", successSummary());

		JsonObject root = new JsonObject();
		root.add("batch", new JsonArray());
		root.getAsJsonArray("batch").add(call);
		return GSON.toJson(root);
	}

	private static JsonObject captureAttributes(String threadId, FirstPersonScreenshotService.CapturedScreenshot capture) {
		JsonObject attributes = new JsonObject();
		if (threadId != null && !threadId.isBlank()) {
			attributes.addProperty("wandb.thread_id", threadId);
			attributes.addProperty("airicraft.thread_id", threadId);
		}
		attributes.addProperty("wandb.is_turn", true);
		attributes.addProperty("airicraft.turn", true);
		attributes.addProperty("airicraft.tool_name", "take_a_look");
		attributes.addProperty("airicraft.has_image_attachment", true);
		attributes.addProperty("airicraft.image.mime_type", TraceSanitizer.sanitizedMimeType(capture));
		attributes.addProperty("airicraft.image.width", capture.width());
		attributes.addProperty("airicraft.image.height", capture.height());
		attributes.addProperty("airicraft.image.source_width", capture.sourceWidth());
		attributes.addProperty("airicraft.image.source_height", capture.sourceHeight());
		attributes.addProperty("airicraft.image.captured_at_ms", capture.capturedAtMs());
		attributes.addProperty("weave.span.kind", "tool");
		attributes.addProperty("openinference.span.kind", "tool");
		return attributes;
	}

	private static JsonObject successSummary() {
		JsonObject weave = new JsonObject();
		weave.addProperty("status", "success");
		weave.addProperty("latency_ms", 1);
		JsonObject summary = new JsonObject();
		summary.add("weave", weave);
		return summary;
	}

	private static Instant captureInstant(FirstPersonScreenshotService.CapturedScreenshot capture) {
		long capturedAtMs = capture.capturedAtMs();
		if (capturedAtMs > 0L) {
			return Instant.ofEpochMilli(capturedAtMs);
		}
		return Instant.now();
	}

	private static Optional<String> authorizationHeader(AgentConfig.ObservabilityConfig config) {
		String explicitAuthorization = config.otlpHeaders().getOrDefault(AUTHORIZATION_HEADER, "");
		if (!explicitAuthorization.isBlank()) {
			return Optional.of(explicitAuthorization);
		}
		String apiKey = config.otlpHeaders().getOrDefault(WANDB_API_KEY_HEADER, "");
		if (apiKey.isBlank()) {
			return Optional.empty();
		}
		String basic = Base64.getEncoder().encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));
		return Optional.of("Basic " + basic);
	}

	private static Optional<URI> completeEndpoint(String otlpEndpoint, String entity, String project) {
		if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
			return Optional.empty();
		}
		try {
			URI otlpUri = URI.create(otlpEndpoint);
			if (otlpUri.getScheme() == null || otlpUri.getHost() == null || otlpUri.getHost().isBlank()) {
				return Optional.empty();
			}
			String path = "/v2/%s/%s/calls/complete".formatted(
				URLEncoder.encode(entity, StandardCharsets.UTF_8),
				URLEncoder.encode(project, StandardCharsets.UTF_8)
			);
			return Optional.of(new URI(
				otlpUri.getScheme(),
				null,
				otlpUri.getHost(),
				otlpUri.getPort(),
				path,
				null,
				null
			));
		}
		catch (IllegalArgumentException | java.net.URISyntaxException exception) {
			return Optional.empty();
		}
	}
}
