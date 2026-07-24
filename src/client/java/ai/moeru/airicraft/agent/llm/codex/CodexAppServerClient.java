package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class CodexAppServerClient implements AutoCloseable {
	private static final int SHUTDOWN_TIMEOUT_MILLIS = 2_000;

	private final List<String> command;
	private final int startupTimeoutMillis;
	private final AtomicLong nextRequestId = new AtomicLong(1L);
	private final Map<Long, CompletableFuture<JsonElement>> pendingRequests = new ConcurrentHashMap<>();
	private final Map<String, TurnAccumulator> turns = new ConcurrentHashMap<>();
	private final Object writeLock = new Object();

	private volatile Process process;
	private volatile BufferedWriter writer;
	private volatile IOException terminalFailure;
	private volatile boolean initialized;

	public CodexAppServerClient(String executable, int startupTimeoutMillis) {
		this(List.of(Objects.requireNonNull(executable, "executable"), "app-server", "--stdio"), startupTimeoutMillis);
	}

	CodexAppServerClient(List<String> command, int startupTimeoutMillis) {
		this.command = List.copyOf(command);
		this.startupTimeoutMillis = Math.max(1, startupTimeoutMillis);
	}

	public synchronized void start() throws IOException, TimeoutException, InterruptedException {
		if (initialized && process != null && process.isAlive()) {
			return;
		}
		if (command.isEmpty() || command.getFirst().isBlank()) {
			throw new IOException("Codex app-server executable is not configured");
		}

		Process started = new ProcessBuilder(command).start();
		process = started;
		writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
		terminalFailure = null;
		startReader(started);
		startStderrDrain(started);

		JsonObject clientInfo = new JsonObject();
		clientInfo.addProperty("name", "airicraft");
		clientInfo.addProperty("title", "Airicraft");
		clientInfo.addProperty("version", "1");
		JsonObject params = new JsonObject();
		params.add("clientInfo", clientInfo);
		try {
			requestInternal("initialize", params).get(startupTimeoutMillis, TimeUnit.MILLISECONDS);
			notifyInternal("initialized", new JsonObject());
			initialized = true;
		}
		catch (ExecutionException exception) {
			close();
			throw asIOException(exception.getCause());
		}
		catch (IOException | TimeoutException | InterruptedException exception) {
			close();
			throw exception;
		}
	}

	public JsonObject request(String method, JsonObject params, int timeoutMillis)
		throws IOException, TimeoutException, InterruptedException {
		start();
		try {
			JsonElement result = requestInternal(method, params).get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
			return result == null || !result.isJsonObject() ? new JsonObject() : result.getAsJsonObject();
		}
		catch (ExecutionException exception) {
			throw asIOException(exception.getCause());
		}
	}

	public CompletableFuture<JsonElement> requestAsync(String method, JsonObject params) throws IOException {
		try {
			start();
		}
		catch (TimeoutException exception) {
			throw new IOException("Timed out initializing Codex app-server", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted initializing Codex app-server", exception);
		}
		return requestInternal(method, params);
	}

	public TurnHandle startTurn(JsonObject params, int timeoutMillis)
		throws IOException, TimeoutException, InterruptedException {
		JsonObject result = request("turn/start", params, timeoutMillis);
		JsonObject turn = object(result, "turn");
		String turnId = string(turn, "id");
		String threadId = string(params, "threadId");
		if (turnId == null || threadId == null) {
			throw new IOException("Codex app-server turn/start response is missing identifiers");
		}
		TurnAccumulator accumulator = turns.computeIfAbsent(turnId, ignored -> new TurnAccumulator(threadId, turnId));
		return new TurnHandle(threadId, turnId, accumulator.completion());
	}

	public void interrupt(String threadId, String turnId) {
		if (threadId == null || turnId == null || !initialized) {
			return;
		}
		JsonObject params = new JsonObject();
		params.addProperty("threadId", threadId);
		params.addProperty("turnId", turnId);
		try {
			requestAsync("turn/interrupt", params);
		}
		catch (IOException exception) {
			Airicraft.LOGGER.debug("Failed to dispatch Codex turn interruption", exception);
		}
	}

	private CompletableFuture<JsonElement> requestInternal(String method, JsonObject params) throws IOException {
		long id = nextRequestId.getAndIncrement();
		CompletableFuture<JsonElement> future = new CompletableFuture<>();
		pendingRequests.put(id, future);
		JsonObject request = new JsonObject();
		request.addProperty("method", method);
		request.addProperty("id", id);
		request.add("params", params == null ? new JsonObject() : params);
		try {
			write(request);
		}
		catch (IOException exception) {
			pendingRequests.remove(id);
			future.completeExceptionally(exception);
			throw exception;
		}
		return future;
	}

	private void notifyInternal(String method, JsonObject params) throws IOException {
		JsonObject notification = new JsonObject();
		notification.addProperty("method", method);
		notification.add("params", params == null ? new JsonObject() : params);
		write(notification);
	}

	private void write(JsonObject message) throws IOException {
		IOException failure = terminalFailure;
		if (failure != null) {
			throw failure;
		}
		BufferedWriter activeWriter = writer;
		if (activeWriter == null) {
			throw new IOException("Codex app-server is not running");
		}
		synchronized (writeLock) {
			activeWriter.write(message.toString());
			activeWriter.newLine();
			activeWriter.flush();
		}
	}

	private void startReader(Process activeProcess) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					handleMessage(JsonParser.parseString(line).getAsJsonObject());
				}
				failAll(new IOException("Codex app-server closed its stdout stream"));
			}
			catch (Exception exception) {
				failAll(asIOException(exception));
			}
		}, "airicraft-codex-app-server-reader");
		thread.setDaemon(true);
		thread.start();
	}

	private void startStderrDrain(Process activeProcess) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
				while (reader.readLine() != null) {
					// Drain stderr so the child cannot block. Protocol failures are reported through JSON-RPC.
				}
			}
			catch (IOException ignored) {
			}
		}, "airicraft-codex-app-server-stderr");
		thread.setDaemon(true);
		thread.start();
	}

	private void handleMessage(JsonObject message) throws IOException {
		if (message.has("id") && !message.has("method")) {
			long id = message.get("id").getAsLong();
			CompletableFuture<JsonElement> future = pendingRequests.remove(id);
			if (future == null) {
				return;
			}
			if (message.has("error") && message.get("error").isJsonObject()) {
				JsonObject error = message.getAsJsonObject("error");
				future.completeExceptionally(new IOException("Codex app-server error: " + string(error, "message")));
			}
			else {
				future.complete(message.get("result"));
			}
			return;
		}
		if (message.has("id") && message.has("method")) {
			rejectServerRequest(message);
			return;
		}
		String method = string(message, "method");
		JsonObject params = object(message, "params");
		if ("item/completed".equals(method)) {
			handleItemCompleted(params);
		}
		else if ("turn/completed".equals(method)) {
			handleTurnCompleted(params);
		}
	}

	private void rejectServerRequest(JsonObject request) throws IOException {
		JsonObject error = new JsonObject();
		error.addProperty("code", -32601);
		error.addProperty("message", "Airicraft does not expose Codex-side effect handlers");
		JsonObject response = new JsonObject();
		response.add("id", request.get("id"));
		response.add("error", error);
		write(response);
	}

	private void handleItemCompleted(JsonObject params) {
		String turnId = string(params, "turnId");
		String threadId = string(params, "threadId");
		JsonObject item = object(params, "item");
		if (turnId == null || item == null || !"agentMessage".equals(string(item, "type"))) {
			return;
		}
		turns.computeIfAbsent(turnId, ignored -> new TurnAccumulator(threadId, turnId)).setAgentMessage(string(item, "text"));
	}

	private void handleTurnCompleted(JsonObject params) {
		JsonObject turn = object(params, "turn");
		String turnId = string(turn, "id");
		String threadId = string(params, "threadId");
		if (turnId == null) {
			return;
		}
		TurnAccumulator accumulator = turns.computeIfAbsent(turnId, ignored -> new TurnAccumulator(threadId, turnId));
		accumulator.complete(string(turn, "status"), agentMessageFromTurn(turn), turn == null ? null : turn.get("error"));
	}

	private static String agentMessageFromTurn(JsonObject turn) {
		if (turn == null || !turn.has("items") || !turn.get("items").isJsonArray()) {
			return null;
		}
		String last = null;
		for (JsonElement element : turn.getAsJsonArray("items")) {
			if (element.isJsonObject() && "agentMessage".equals(string(element.getAsJsonObject(), "type"))) {
				last = string(element.getAsJsonObject(), "text");
			}
		}
		return last;
	}

	private void failAll(IOException exception) {
		terminalFailure = exception;
		pendingRequests.values().forEach(future -> future.completeExceptionally(exception));
		pendingRequests.clear();
		turns.values().forEach(turn -> turn.completion().completeExceptionally(exception));
	}

	@Override
	public synchronized void close() {
		initialized = false;
		Process activeProcess = process;
		process = null;
		writer = null;
		if (activeProcess != null) {
			activeProcess.destroy();
			try {
				if (!activeProcess.waitFor(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
					activeProcess.destroyForcibly();
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				activeProcess.destroyForcibly();
			}
		}
		failAll(new IOException("Codex app-server client closed"));
		turns.clear();
	}

	private static IOException asIOException(Throwable throwable) {
		if (throwable instanceof IOException exception) {
			return exception;
		}
		return new IOException(throwable == null ? "Codex app-server operation failed" : throwable.getMessage(), throwable);
	}

	private static JsonObject object(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
	}

	private static String string(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
			return null;
		}
		try {
			return parent.get(key).getAsString();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	public record TurnHandle(String threadId, String turnId, CompletableFuture<TurnResult> completion) {
	}

	public record TurnResult(String threadId, String turnId, String status, String agentMessage, JsonElement error) {
		public TurnResult {
			error = error == null || error.isJsonNull() ? null : error.deepCopy();
		}
	}

	private static final class TurnAccumulator {
		private final String threadId;
		private final String turnId;
		private final CompletableFuture<TurnResult> completion = new CompletableFuture<>();
		private volatile String agentMessage;

		private TurnAccumulator(String threadId, String turnId) {
			this.threadId = threadId;
			this.turnId = turnId;
		}

		private CompletableFuture<TurnResult> completion() {
			return completion;
		}

		private void setAgentMessage(String text) {
			if (text != null) {
				agentMessage = text;
			}
		}

		private void complete(String status, String fallbackMessage, JsonElement error) {
			setAgentMessage(fallbackMessage);
			completion.complete(new TurnResult(threadId, turnId, status, agentMessage, error));
		}
	}
}
