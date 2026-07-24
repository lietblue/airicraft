package ai.moeru.airicraft.agent.llm.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerClientTest {
	@TempDir
	Path tempDir;

	@Test
	void initializesAndStreamsStructuredTurnCompletion() throws Exception {
		Path log = tempDir.resolve("fake-app-server.log");
		try (CodexAppServerClient client = fakeClient(log)) {
			JsonObject threadParams = new JsonObject();
			threadParams.addProperty("ephemeral", true);
			JsonObject thread = client.request("thread/start", threadParams, 2_000);
			assertEquals("root", thread.getAsJsonObject("thread").get("id").getAsString());

			JsonObject forkParams = new JsonObject();
			forkParams.addProperty("threadId", "root");
			forkParams.addProperty("ephemeral", true);
			JsonObject fork = client.request("thread/fork", forkParams, 2_000);
			String forkId = fork.getAsJsonObject("thread").get("id").getAsString();

			JsonObject turnParams = new JsonObject();
			turnParams.addProperty("threadId", forkId);
			turnParams.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"hello\"}]").getAsJsonArray());
			turnParams.add("outputSchema", new JsonObject());
			CodexAppServerClient.TurnHandle handle = client.startTurn(turnParams, 2_000);
			CodexAppServerClient.TurnResult result = handle.completion().get(2, TimeUnit.SECONDS);

			assertEquals("completed", result.status());
			assertTrue(result.agentMessage().contains("chatMessages"));
			assertTrue(result.agentMessage().contains(forkId));
		}

		String wireLog = Files.readString(log);
		assertTrue(wireLog.contains("initialize"));
		assertTrue(wireLog.contains("initialized"));
		assertTrue(wireLog.contains("thread/start"));
		assertTrue(wireLog.contains("thread/fork root"));
		assertTrue(wireLog.contains("turn/start"));
	}

	@Test
	void interruptRequestCompletesWaitingTurnAsInterrupted() throws Exception {
		Path log = tempDir.resolve("interrupt.log");
		try (CodexAppServerClient client = fakeClient(log)) {
			JsonObject threadParams = new JsonObject();
			threadParams.addProperty("ephemeral", true);
			client.request("thread/start", threadParams, 2_000);

			JsonObject turnParams = new JsonObject();
			turnParams.addProperty("threadId", "root");
			turnParams.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"WAIT\"}]").getAsJsonArray());
			CodexAppServerClient.TurnHandle handle = client.startTurn(turnParams, 2_000);

			client.interrupt(handle.threadId(), handle.turnId());
			CodexAppServerClient.TurnResult result = handle.completion().get(2, TimeUnit.SECONDS);
			assertEquals("interrupted", result.status());
		}

		assertTrue(Files.readString(log).contains("turn/interrupt"));
	}

	static CodexAppServerClient fakeClient(Path log) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return new CodexAppServerClient(List.of(
			java,
			"-Dairicraft.fake.codex.log=" + log.toAbsolutePath(),
			"-cp",
			System.getProperty("java.class.path"),
			FakeAppServer.class.getName()
		), 2_000);
	}

	public static final class FakeAppServer {
		private FakeAppServer() {
		}

		public static void main(String[] args) throws Exception {
			Path log = Path.of(System.getProperty("airicraft.fake.codex.log"));
			int forkCount = 0;
			int turnCount = 0;
			try (
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
			) {
				String line;
				while ((line = reader.readLine()) != null) {
					JsonObject request = JsonParser.parseString(line).getAsJsonObject();
					String method = request.get("method").getAsString();
					JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
					String suffix = "thread/fork".equals(method) ? " " + params.get("threadId").getAsString() : "";
					append(log, method + suffix);
					if (!request.has("id")) {
						continue;
					}

					JsonElement id = request.get("id");
					switch (method) {
						case "initialize" -> respond(writer, id, new JsonObject());
						case "thread/start" -> respond(writer, id, threadResponse("root"));
						case "thread/fork" -> {
							forkCount++;
							respond(writer, id, threadResponse("fork-from-" + params.get("threadId").getAsString() + "-" + forkCount));
						}
						case "turn/start" -> {
							turnCount++;
							String turnId = "turn-" + turnCount;
							String threadId = params.get("threadId").getAsString();
							JsonObject result = new JsonObject();
							JsonObject turn = new JsonObject();
							turn.addProperty("id", turnId);
							turn.addProperty("status", "inProgress");
							turn.add("items", new com.google.gson.JsonArray());
							result.add("turn", turn);
							respond(writer, id, result);
							String input = params.get("input").toString();
							if (!input.contains("WAIT")) {
								completeTurn(writer, threadId, turnId, "completed", structuredReply(threadId, input));
							}
						}
						case "turn/interrupt" -> {
							respond(writer, id, new JsonObject());
							completeTurn(
								writer,
								params.get("threadId").getAsString(),
								params.get("turnId").getAsString(),
								"interrupted",
								null
							);
						}
						default -> respond(writer, id, new JsonObject());
					}
				}
			}
		}

		private static JsonObject threadResponse(String threadId) {
			JsonObject result = new JsonObject();
			JsonObject thread = new JsonObject();
			thread.addProperty("id", threadId);
			result.add("thread", thread);
			result.addProperty("model", "fake-codex");
			return result;
		}

		private static String structuredReply(String threadId, String input) {
			JsonObject message = new JsonObject();
			if (input.contains("REQUEST_TOOL")) {
				message.add("chatMessages", new com.google.gson.JsonArray());
				com.google.gson.JsonArray tools = new com.google.gson.JsonArray();
				JsonObject tool = new JsonObject();
				tool.addProperty("name", "clear_goal");
				tool.addProperty("argumentsJson", "{}");
				tools.add(tool);
				message.add("toolCalls", tools);
				return message.toString();
			}
			com.google.gson.JsonArray chats = new com.google.gson.JsonArray();
			JsonObject chat = new JsonObject();
			chat.addProperty("text", "from:" + threadId);
			chat.addProperty("delayTicks", 0);
			chats.add(chat);
			message.add("chatMessages", chats);
			message.add("toolCalls", new com.google.gson.JsonArray());
			return message.toString();
		}

		private static void completeTurn(BufferedWriter writer, String threadId, String turnId, String status, String text) throws Exception {
			if (text != null) {
				JsonObject item = new JsonObject();
				item.addProperty("id", "item-" + turnId);
				item.addProperty("type", "agentMessage");
				item.addProperty("text", text);
				JsonObject itemParams = new JsonObject();
				itemParams.addProperty("threadId", threadId);
				itemParams.addProperty("turnId", turnId);
				itemParams.addProperty("completedAtMs", 1L);
				itemParams.add("item", item);
				notify(writer, "item/completed", itemParams);
			}

			JsonObject turn = new JsonObject();
			turn.addProperty("id", turnId);
			turn.addProperty("status", status);
			turn.add("items", new com.google.gson.JsonArray());
			JsonObject completedParams = new JsonObject();
			completedParams.addProperty("threadId", threadId);
			completedParams.add("turn", turn);
			notify(writer, "turn/completed", completedParams);
		}

		private static void respond(BufferedWriter writer, JsonElement id, JsonObject result) throws Exception {
			JsonObject response = new JsonObject();
			response.add("id", id);
			response.add("result", result);
			write(writer, response);
		}

		private static void notify(BufferedWriter writer, String method, JsonObject params) throws Exception {
			JsonObject notification = new JsonObject();
			notification.addProperty("method", method);
			notification.add("params", params);
			write(writer, notification);
		}

		private static void write(BufferedWriter writer, JsonObject message) throws Exception {
			writer.write(message.toString());
			writer.newLine();
			writer.flush();
		}

		private static void append(Path log, String line) throws Exception {
			Files.writeString(
				log,
				line + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		}
	}
}
