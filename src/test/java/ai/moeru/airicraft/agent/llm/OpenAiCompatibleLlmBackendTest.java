package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmBackendTest {
	@Test
	void generateParsesPlannerResponseAndUsage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent follow me", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Sure, I'll follow you.", result.payload().replyText());
			assertEquals("set_goal", result.payload().intent().type());
			assertEquals(Integer.valueOf(1234), result.usage().promptTokens());
			assertEquals(Integer.valueOf(56), result.usage().completionTokens());
			assertEquals(Integer.valueOf(1290), result.usage().totalTokens());

			String body = bodyRef.get();
			assertTrue(body.contains("\"role\":\"system\""));
			assertTrue(body.contains("Alice said just now"));
		}
	}

	@Test
	void generateBuildsMultimodalPlannerRequestWhenImageAttached() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"high",
				true
			));

			backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.userWithImage(
					"Tool result for take_a_look: current first-person view attached.",
					LlmMessageKind.TOOL_RESULT,
					new LlmImageAttachment("image/png", new byte[]{1, 2, 3}, "high")
				)
			)));

			String body = bodyRef.get();
			assertTrue(body.contains("\"type\":\"image_url\""));
			assertTrue(body.contains("\"detail\":\"high\""));
			assertTrue(body.contains("data:image/png;base64,AQID"));
			assertTrue(body.contains("Tool result for take_a_look"));
		}
	}

	@Test
	void generateCompactsConsecutiveUserMessagesIntoSingleOutboundMessage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Context update: It is nighttime.", LlmMessageKind.NOTICE),
				LlmChatMessage.user("Alice said just now: hi", LlmMessageKind.USER_TURN),
				LlmChatMessage.assistant("Agent replied just now: hello"),
				LlmChatMessage.user("Context update: Goal is still none.", LlmMessageKind.NOTICE)
			)));

			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			JsonArray messages = body.getAsJsonArray("messages");
			assertEquals(4, messages.size());
			assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
			assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
			assertEquals(
				"Context update: It is nighttime.\n\nAlice said just now: hi",
				messages.get(1).getAsJsonObject().get("content").getAsString()
			);
			assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
			assertEquals("user", messages.get(3).getAsJsonObject().get("role").getAsString());
		}
	}

	@Test
	void generateCompactsTextAndImageUserMessagesIntoSingleMultimodalOutboundMessage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"high",
				true
			));

			backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Context update: Checking the current view.", LlmMessageKind.NOTICE),
				LlmChatMessage.userWithImage(
					"Tool result for take_a_look: current first-person view attached.",
					LlmMessageKind.TOOL_RESULT,
					new LlmImageAttachment("image/png", new byte[]{1, 2, 3}, "high")
				)
			)));

			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			JsonArray messages = body.getAsJsonArray("messages");
			assertEquals(2, messages.size());
			JsonObject mergedUser = messages.get(1).getAsJsonObject();
			assertEquals("user", mergedUser.get("role").getAsString());
			JsonArray content = mergedUser.getAsJsonArray("content");
			assertEquals(3, content.size());
			assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
			assertEquals("Context update: Checking the current view.", content.get(0).getAsJsonObject().get("text").getAsString());
			assertEquals("text", content.get(1).getAsJsonObject().get("type").getAsString());
			assertTrue(content.get(1).getAsJsonObject().get("text").getAsString().contains("Tool result for take_a_look"));
			assertEquals("image_url", content.get(2).getAsJsonObject().get("type").getAsString());
		}
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(HttpServer server) {
			this.server = server;
		}

		private static TestServer start(AtomicReference<String> bodyRef) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef));
			server.start();
			return new TestServer(server);
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponse(exchange, 200, """
				{
				  "choices": [
				    {
				      "message": {
				        "content": "{\\"replyText\\":\\"Sure, I'll follow you.\\",\\"intent\\":{\\"type\\":\\"set_goal\\",\\"goalType\\":\\"FOLLOW_PLAYER\\",\\"targetPlayer\\":\\"Alice\\"},\\"toolRequest\\":null}"
				      }
				    }
				  ],
				  "usage": {
				    "prompt_tokens": 1234,
				    "completion_tokens": 56,
				    "total_tokens": 1290
				  }
				}
				""");
		}

		private int port() {
			return server.getAddress().getPort();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
