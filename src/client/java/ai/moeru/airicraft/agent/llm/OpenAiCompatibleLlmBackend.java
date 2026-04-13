package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
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
			JsonObject eventPolicyObject = payload.has("eventPolicyChanges") && payload.get("eventPolicyChanges").isJsonObject()
				? payload.getAsJsonObject("eventPolicyChanges")
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
			EventPolicyChanges eventPolicyChanges = parseEventPolicyChanges(eventPolicyObject);
			Airicraft.LOGGER.info(
				"Planner parsed response intentType={} goalType={} targetPlayer={} replyText={} toolRequestType={} toolPrompt={} policyChangeCount={}",
				intent.type(),
				intent.goalType(),
				intent.targetPlayer(),
				summarizeForLog(replyText),
				toolRequest == null ? null : toolRequest.type(),
				toolRequest == null ? null : summarizeForLog(toolRequest.prompt()),
				eventPolicyChanges == null ? 0 : eventPolicyChanges.upserts().size()
			);
			return new PlannerResponse(replyText, intent, toolRequest, eventPolicyChanges);
		}
		catch (IllegalArgumentException | JsonParseException exception) {
			Airicraft.LOGGER.warn("Failed to parse planner response body={}", summarizeForLog(responseBody), exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse planner response", exception);
		}
	}

	private static EventPolicyChanges parseEventPolicyChanges(JsonObject object) {
		if (object == null) {
			return null;
		}

		boolean clearAll = getBoolean(object, "clearAll").orElse(false);
		List<String> removeRuleIds = getStringArray(object, "removeRuleIds");
		List<EventPolicyRuleUpsert> upserts = new ArrayList<>();
		JsonArray upsertArray = object.has("upserts") && object.get("upserts").isJsonArray()
			? object.getAsJsonArray("upserts")
			: null;
		if (upsertArray != null) {
			for (JsonElement element : upsertArray) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject upsertObject = element.getAsJsonObject();
				JsonObject matchObject = upsertObject.has("match") && upsertObject.get("match").isJsonObject()
					? upsertObject.getAsJsonObject("match")
					: null;
				upserts.add(new EventPolicyRuleUpsert(
					getString(upsertObject, "ruleId").orElse(null),
					getString(upsertObject, "effect").orElse(null),
					parseEventPolicyMatch(matchObject),
					getString(upsertObject, "reason").orElse(null)
				));
			}
		}
		return new EventPolicyChanges(clearAll, removeRuleIds, upserts);
	}

	private static EventPolicyMatch parseEventPolicyMatch(JsonObject object) {
		if (object == null) {
			return null;
		}
		return new EventPolicyMatch(
			getString(object, "eventType").orElse(null),
			getString(object, "player").orElse(null),
			getString(object, "speaker").orElse(null),
			getString(object, "actor").orElse(null),
			getString(object, "itemId").orElse(null),
			getString(object, "damageTypeId").orElse(null),
			getString(object, "attackerName").orElse(null)
		);
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
			return Optional.empty();
		}
		String value = object.get(fieldName).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static Optional<Boolean> getBoolean(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
			return Optional.empty();
		}
		return Optional.of(object.get(fieldName).getAsBoolean());
	}

	private static List<String> getStringArray(JsonObject object, String fieldName) {
		JsonArray array = object == null || !object.has(fieldName) || !object.get(fieldName).isJsonArray()
			? null
			: object.getAsJsonArray(fieldName);
		if (array == null) {
			return List.of();
		}
		ArrayList<String> values = new ArrayList<>();
		for (JsonElement element : array) {
			if (element == null || element.isJsonNull()) {
				continue;
			}
			String value = element.getAsString();
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
