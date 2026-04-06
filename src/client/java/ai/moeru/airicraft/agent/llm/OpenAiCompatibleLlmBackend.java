package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public final class OpenAiCompatibleLlmBackend implements LlmBackend {
	private final AgentConfig.LlmConfig config;
	private final OpenAiCompatibleChatClient chatClient;
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		this.chatClient = new OpenAiCompatibleChatClient(config);
	}

	@Override
	public synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");

		Object injected = injectedOutcomes.pollFirst();
		if (injected instanceof PlannerResponse plannerResponse) {
			return LlmCallResult.of(plannerResponse, LlmUsageSnapshot.unknown());
		}
		if (injected instanceof TimeoutException timeoutException) {
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeoutException.getMessage(), timeoutException);
		}

		LlmCallResult<String> rawResponse = chatClient.complete(conversation);
		return LlmCallResult.of(parsePlannerResponse(rawResponse.payload()), rawResponse.usage());
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
				getString(intentObject, "targetPlayer").orElse(null),
				parseGoalPosition(intentObject, "position"),
				parseGoalMineSpec(intentObject, "mineSpec")
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
			Airicraft.LOGGER.warn("Failed to parse planner response body={}", summarizeForLog(responseBody), exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse planner response", exception);
		}
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			String value = object.get(fieldName).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static GoalPosition parseGoalPosition(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject positionObject = object.getAsJsonObject(fieldName);
		Optional<Integer> x = getInt(positionObject, "x");
		Optional<Integer> y = getInt(positionObject, "y");
		Optional<Integer> z = getInt(positionObject, "z");
		if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
			return null;
		}
		return new GoalPosition(x.get(), y.get(), z.get(), getBoolean(positionObject, "exactY").orElse(false));
	}

	private static GoalMineSpec parseGoalMineSpec(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject mineSpecObject = object.getAsJsonObject(fieldName);
		Optional<List<String>> blockIds = getStringArray(mineSpecObject, "blockIds");
		Optional<Integer> quantity = getInt(mineSpecObject, "quantity");
		if (blockIds.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		try {
			return new GoalMineSpec(blockIds.get(), quantity.get());
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static Optional<Integer> getInt(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(fieldName).getAsInt());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> getBoolean(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(fieldName).getAsBoolean());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<List<String>> getStringArray(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonArray()) {
			return Optional.empty();
		}
		JsonArray array = object.getAsJsonArray(fieldName);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			if (!array.get(index).isJsonPrimitive()) {
				return Optional.empty();
			}
			try {
				values.add(array.get(index).getAsString());
			}
			catch (RuntimeException exception) {
				return Optional.empty();
			}
		}
		return Optional.of(values);
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
