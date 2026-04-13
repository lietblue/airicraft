package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannerCompactionService {
	private final OpenAiCompatibleChatClient chatClient;
	private final ExecutorService executorService;

	private CompletableFuture<LlmCallResult<CompactionCheckpoint>> inFlight;

	public PlannerCompactionService(OpenAiCompatibleChatClient chatClient) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
		this.executorService = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-compaction");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean hasInFlight() {
		return inFlight != null;
	}

	public boolean submit(LlmConversation conversation) {
		Objects.requireNonNull(conversation, "conversation");
		if (inFlight != null) {
			return false;
		}

		inFlight = CompletableFuture.supplyAsync(() -> {
			try {
				LlmCallResult<String> response = chatClient.complete(conversation);
				return LlmCallResult.of(parseCheckpoint(response.payload()), response.usage());
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		return true;
	}

	public CompactionExecutionResult poll() {
		if (inFlight == null || !inFlight.isDone()) {
			return null;
		}

		CompletableFuture<LlmCallResult<CompactionCheckpoint>> future = inFlight;
		inFlight = null;
		try {
			LlmCallResult<CompactionCheckpoint> result = future.join();
			return new CompactionExecutionResult(result.payload(), result.usage(), null, null);
		}
		catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof LlmBackendException backendException) {
				return new CompactionExecutionResult(null, LlmUsageSnapshot.unknown(), backendException.failureType(), backendException.getMessage());
			}
			return new CompactionExecutionResult(
				null,
				LlmUsageSnapshot.unknown(),
				LlmFailureType.PROVIDER_ERROR,
				cause == null ? exception.getMessage() : cause.getMessage()
			);
		}
	}

	public void reset() {
		if (inFlight != null) {
			inFlight.cancel(true);
			inFlight = null;
		}
	}

	public void shutdown() {
		reset();
		executorService.shutdownNow();
	}

	private static CompactionCheckpoint parseCheckpoint(String responseBody) throws LlmBackendException {
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
			JsonObject payload = OpenAiCompatibleMessageContent.extractJsonObject(message.get("content"));
			return new CompactionCheckpoint(
				getString(payload, "time_anchor"),
				getString(payload, "session_state"),
				getString(payload, "active_goal"),
				getStringList(payload, "active_commitments"),
				getStringList(payload, "durable_facts"),
				getStringList(payload, "relevant_people"),
				getStringList(payload, "open_loops"),
				getStringList(payload, "recent_timeline"),
				getStringList(payload, "forgettable_noise")
			);
		}
		catch (IllegalStateException | JsonParseException exception) {
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse compaction response", exception);
		}
	}

	private static String getString(JsonObject payload, String fieldName) {
		if (payload == null || !payload.has(fieldName) || payload.get(fieldName).isJsonNull()) {
			return null;
		}
		String value = payload.get(fieldName).getAsString();
		return value == null || value.isBlank() ? null : value;
	}

	private static List<String> getStringList(JsonObject payload, String fieldName) {
		if (payload == null || !payload.has(fieldName) || payload.get(fieldName).isJsonNull()) {
			return List.of();
		}
		if (!payload.get(fieldName).isJsonArray()) {
			String single = payload.get(fieldName).getAsString();
			return single == null || single.isBlank() ? List.of() : List.of(single);
		}
		ArrayList<String> values = new ArrayList<>();
		for (var item : payload.getAsJsonArray(fieldName)) {
			if (item == null || item.isJsonNull()) {
				continue;
			}
			String value = item.getAsString();
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}
}
