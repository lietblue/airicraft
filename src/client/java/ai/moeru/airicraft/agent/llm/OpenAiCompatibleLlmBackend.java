package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public final class OpenAiCompatibleLlmBackend implements LlmBackend {
	private final AgentConfig.LlmConfig config;
	private final OpenAiCompatibleChatClient chatClient;
	private final AgentObservability observability;
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE);
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability) {
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.chatClient = new OpenAiCompatibleChatClient(config, observability);
	}

	@Override
	public synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");

		Object injected = injectedOutcomes.pollFirst();
		if (injected instanceof PlannerResponse plannerResponse) {
			observability.recordLlmResponse(Context.current(), null, config.model(), LlmUsageSnapshot.unknown(), plannerResponse);
			return LlmCallResult.of(plannerResponse, LlmUsageSnapshot.unknown(), null, config.model());
		}
		if (injected instanceof TimeoutException timeoutException) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), timeoutException.getMessage(), timeoutException);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeoutException.getMessage(), timeoutException);
		}

		LlmCallResult<String> rawResponse = chatClient.complete(conversation);
		PlannerResponse plannerResponse = parsePlannerResponse(rawResponse.payload());
		observability.recordLlmResponse(Context.current(), rawResponse.statusCode(), rawResponse.responseModel(), rawResponse.usage(), plannerResponse);
		return LlmCallResult.of(plannerResponse, rawResponse.usage(), rawResponse.statusCode(), rawResponse.responseModel());
	}

	@Override
	public synchronized void injectMockResponse(PlannerResponse response) {
		injectedOutcomes.addLast(Objects.requireNonNull(response, "response"));
	}

	@Override
	public synchronized void injectTimeout() {
		injectedOutcomes.addLast(new TimeoutException("Injected LLM timeout"));
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	private PlannerResponse parsePlannerResponse(String responseBody) throws LlmBackendException {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new JsonParseException("Missing choices");
			}

			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new JsonParseException("Missing message");
			}

			String content = OpenAiCompatibleMessageContent.extract(message.get("content"));
			JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
			String replyText = getString(payload, "replyText").orElse("");
			JsonObject intentObject = payload.has("intent") && payload.get("intent").isJsonObject()
				? payload.getAsJsonObject("intent")
				: new JsonObject();
			JsonObject toolRequestObject = payload.has("toolRequest") && payload.get("toolRequest").isJsonObject()
				? payload.getAsJsonObject("toolRequest")
				: null;

			PlannerIntent intent = new PlannerIntent(
				getString(intentObject, "type").orElse("none").toLowerCase(Locale.ROOT),
				getString(intentObject, "goalType")
					.map(value -> ai.moeru.airicraft.agent.goals.GoalType.valueOf(value.toUpperCase(Locale.ROOT)))
					.orElse(null),
				getString(intentObject, "targetPlayer").orElse(null)
			);
			PlannerToolRequest toolRequest = toolRequestObject == null
				? null
				: new PlannerToolRequest(
					getString(toolRequestObject, "type").orElse(null),
					getString(toolRequestObject, "prompt").orElse(null)
				);
			Airicraft.LOGGER.info(
				"Planner parsed response intentType={} goalType={} targetPlayer={} replyText={} toolRequestType={} toolPrompt={}",
				intent.type(),
				intent.goalType(),
				intent.targetPlayer(),
				summarizeForLog(replyText),
				toolRequest == null ? null : toolRequest.type(),
				toolRequest == null ? null : summarizeForLog(toolRequest.prompt())
			);
			return new PlannerResponse(replyText, intent, toolRequest);
		}
		catch (IllegalArgumentException | JsonParseException exception) {
			Airicraft.LOGGER.warn("Failed to parse planner response summary={}", TraceSanitizer.summarizeChatResponseForLog(responseBody), exception);
			observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), "Failed to parse planner response", exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse planner response", exception);
		}
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
			return Optional.empty();
		}
		String value = object.get(fieldName).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
