package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
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
	void generateParsesEventPolicyChanges() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"\\",\\"intent\\":{\\"type\\":\\"none\\",\\"goalType\\":null,\\"targetPlayer\\":null},\\"toolRequest\\":null,\\"eventPolicyChanges\\":{\\"clearAll\\":false,\\"removeRuleIds\\":[\\"old-rule\\"],\\"upserts\\":[{\\"ruleId\\":\\"mute-system\\",\\"effect\\":\\"ignore\\",\\"match\\":{\\"eventType\\":\\"social.system_message\\",\\"speaker\\":\\"server\\"},\\"reason\\":\\"Mute repeated system spam\\"}]}}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 5,
			    "total_tokens": 15
			  }
			}
			""")) {
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
				LlmChatMessage.user("Recent updates", LlmMessageKind.USER_TURN)
			)));

			assertEquals(List.of("old-rule"), result.payload().eventPolicyChanges().removeRuleIds());
			assertEquals(1, result.payload().eventPolicyChanges().upserts().size());
			assertEquals("mute-system", result.payload().eventPolicyChanges().upserts().getFirst().ruleId());
			assertEquals("social.system_message", result.payload().eventPolicyChanges().upserts().getFirst().match().eventType());
		}
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;
		private final String responseBody;

		private TestServer(HttpServer server, String responseBody) {
			this.server = server;
			this.responseBody = responseBody;
		}

		private static TestServer start(AtomicReference<String> bodyRef) throws IOException {
			return start(bodyRef, """
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

		private static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.start();
			return new TestServer(server, responseBody);
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef, String responseBody) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponse(exchange, 200, responseBody);
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
