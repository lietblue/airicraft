package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenAiCompatibleChatClient {
	private static final Gson GSON = new Gson();

	private final AgentConfig.LlmConfig config;
	private final AgentObservability observability;
	private final HttpClient httpClient = HttpClient.newHttpClient();

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE);
	}

	public OpenAiCompatibleChatClient(AgentConfig.LlmConfig config, AgentObservability observability) {
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
	}

	LlmCallResult<String> complete(LlmConversation conversation) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");
		if (!config.isConfigured()) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "LLM provider is not configured");
		}

		String requestBody = GSON.toJson(buildRequestPayload(conversation));
		URI uri;
		try {
			uri = buildUri();
		}
		catch (LlmBackendException exception) {
			observability.recordFailure(Context.current(), exception.failureType().name(), exception.getMessage(), exception);
			throw exception;
		}
		observability.recordLlmRequest(
			Context.current(),
			TraceSanitizer.inferProviderName(config.providerBaseUrl()),
			uri,
			config.model(),
			config.requestTimeoutMillis(),
			conversation,
			requestBody
		);
		Airicraft.LOGGER.info(
			"LLM request model={} messages={} preview={}",
			config.model(),
			conversation.messages().size(),
			TraceSanitizer.summarizeForLog(TraceSanitizer.sanitizeRequestPayloadForTrace(requestBody))
		);
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofMillis(config.requestTimeoutMillis()))
			.header("Authorization", "Bearer " + config.apiKey())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
			.build();

		try {
			HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			Airicraft.LOGGER.info(
				"LLM response model={} status={} summary={}",
				config.model(),
				response.statusCode(),
				TraceSanitizer.summarizeChatResponseForLog(response.body())
			);
			if (response.statusCode() >= 400) {
				observability.recordFailure(
					Context.current(),
					LlmFailureType.PROVIDER_ERROR.name(),
					"Provider returned HTTP " + response.statusCode(),
					null
				);
				throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "Provider returned HTTP " + response.statusCode());
			}
			return LlmCallResult.of(
				response.body(),
				parseUsage(response.body()),
				response.statusCode(),
				responseModel(response.body()).orElse(config.model())
			);
		}
		catch (java.net.http.HttpTimeoutException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), "LLM request timed out", exception);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, "LLM request timed out", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), "LLM request interrupted", exception);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, "LLM request interrupted", exception);
		}
		catch (IOException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_ERROR.name(), "LLM request failed", exception);
			throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "LLM request failed", exception);
		}
	}

	private URI buildUri() throws LlmBackendException {
		try {
			String baseUrl = config.providerBaseUrl().endsWith("/")
				? config.providerBaseUrl().substring(0, config.providerBaseUrl().length() - 1)
				: config.providerBaseUrl();
			return URI.create(baseUrl + "/chat/completions");
		}
		catch (IllegalArgumentException exception) {
			throw new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Invalid LLM provider URL", exception);
		}
	}

	private Map<String, Object> buildRequestPayload(LlmConversation conversation) {
		return Map.of(
			"model", config.model(),
			"response_format", Map.of("type", "json_object"),
			"messages", compactRequestMessages(conversation.messages())
		);
	}

	private Map<String, Object> toRequestMessage(LlmChatMessage message) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("role", message.role());
		if (message.rawContentOverride() != null) {
			payload.put("content", message.rawContentOverride());
		}
		else {
			payload.put("content", message.hasImageAttachment() ? multimodalContent(message) : message.content());
		}
		return payload;
	}

	private List<Map<String, Object>> compactRequestMessages(List<LlmChatMessage> messages) {
		ArrayList<Map<String, Object>> compacted = new ArrayList<>();
		for (LlmChatMessage message : messages) {
			Map<String, Object> requestMessage = toRequestMessage(message);
			if (!compacted.isEmpty() && shouldMergeUserMessage(compacted.getLast(), requestMessage)) {
				compacted.set(compacted.size() - 1, mergeUserMessages(compacted.getLast(), requestMessage));
				continue;
			}
			compacted.add(requestMessage);
		}
		return List.copyOf(compacted);
	}

	private static boolean shouldMergeUserMessage(Map<String, Object> previous, Map<String, Object> current) {
		return "user".equals(previous.get("role")) && "user".equals(current.get("role"));
	}

	private static Map<String, Object> mergeUserMessages(Map<String, Object> previous, Map<String, Object> current) {
		LinkedHashMap<String, Object> merged = new LinkedHashMap<>(previous);
		merged.put("content", mergeUserContent(previous.get("content"), current.get("content")));
		return merged;
	}

	private static Object mergeUserContent(Object previous, Object current) {
		if (previous instanceof String previousText && current instanceof String currentText) {
			if (previousText.isBlank()) {
				return currentText;
			}
			if (currentText.isBlank()) {
				return previousText;
			}
			return previousText + "\n\n" + currentText;
		}
		ArrayList<Map<String, Object>> parts = new ArrayList<>();
		appendContentParts(parts, previous);
		appendContentParts(parts, current);
		return List.copyOf(parts);
	}

	@SuppressWarnings("unchecked")
	private static void appendContentParts(List<Map<String, Object>> parts, Object content) {
		if (content == null) {
			return;
		}
		if (content instanceof String text) {
			if (!text.isBlank()) {
				parts.add(Map.of("type", "text", "text", text));
			}
			return;
		}
		if (content instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					parts.add((Map<String, Object>) map);
				}
			}
		}
	}

	private static List<Map<String, Object>> multimodalContent(LlmChatMessage message) {
		LlmImageAttachment imageAttachment = Objects.requireNonNull(message.imageAttachment(), "imageAttachment");
		String imageUrl = "data:%s;base64,%s".formatted(
			imageAttachment.mimeType(),
			Base64.getEncoder().encodeToString(imageAttachment.imageBytes())
		);
		return List.of(
			Map.of("type", "text", "text", message.content()),
			Map.of(
				"type", "image_url",
				"image_url", Map.of(
					"url", imageUrl,
					"detail", imageAttachment.detail()
				)
			)
		);
	}

	static LlmUsageSnapshot parseUsage(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			if (!root.has("usage") || !root.get("usage").isJsonObject()) {
				return LlmUsageSnapshot.unknown();
			}
			JsonObject usage = root.getAsJsonObject("usage");
			Integer promptTokens = getUsageInt(usage, "prompt_tokens");
			if (promptTokens == null) {
				promptTokens = getUsageInt(usage, "input_tokens");
			}
			Integer completionTokens = getUsageInt(usage, "completion_tokens");
			if (completionTokens == null) {
				completionTokens = getUsageInt(usage, "output_tokens");
			}
			Integer totalTokens = getUsageInt(usage, "total_tokens");
			return new LlmUsageSnapshot(promptTokens, completionTokens, totalTokens);
		}
		catch (IllegalStateException | JsonParseException exception) {
			return LlmUsageSnapshot.unknown();
		}
	}

	private static Integer getUsageInt(JsonObject usage, String fieldName) {
		if (usage == null || !usage.has(fieldName) || usage.get(fieldName).isJsonNull()) {
			return null;
		}
		return usage.get(fieldName).getAsInt();
	}

	static java.util.Optional<String> responseModel(String responseBody) {
		return TraceSanitizer.responseModel(responseBody);
	}

	static String summarizeForLog(String text) {
		return TraceSanitizer.summarizeForLog(text);
	}
}
