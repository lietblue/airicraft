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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerCompactionServiceTest {
	@Test
	void pollParsesCheckpointWhenProviderPrependsThoughtBlock() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "<thought>Compress conversation into checkpoint.</thought>{\\"time_anchor\\":\\"Day 3 morning\\",\\"session_state\\":\\"singleplayer loaded\\",\\"active_goal\\":\\"Collect 16 wood logs\\",\\"active_commitments\\":[\\"Keep inventory logs\\"],\\"durable_facts\\":[\\"Base at spawn\\"],\\"relevant_people\\":[\\"developer/admin\\"],\\"open_loops\\":[\\"Need axe\\"],\\"recent_timeline\\":[\\"Joined world\\"],\\"forgettable_noise\\":[\\"Greeting chatter\\"]}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 100,
			    "completion_tokens": 20,
			    "total_tokens": 120
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleChatClient chatClient = new OpenAiCompatibleChatClient(new AgentConfig.LlmConfig(
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
			PlannerCompactionService service = new PlannerCompactionService(chatClient);
			try {
				assertTrue(service.submit(LlmConversation.of(List.of(
					LlmChatMessage.system(PlannerPromptPolicy.compactionInstruction()),
					LlmChatMessage.user("Context checkpoint request", LlmMessageKind.NOTICE)
				))));

				CompactionExecutionResult result = waitForResult(service);
				assertNotNull(result);
				assertTrue(result.succeeded());
				assertNull(result.failureType());
				assertEquals(
					new CompactionCheckpoint(
						"Day 3 morning",
						"singleplayer loaded",
						"Collect 16 wood logs",
						List.of("Keep inventory logs"),
						List.of("Base at spawn"),
						List.of("developer/admin"),
						List.of("Need axe"),
						List.of("Joined world"),
						List.of("Greeting chatter")
					),
					result.checkpoint()
				);
			}
			finally {
				service.shutdown();
			}
		}
	}

	private static CompactionExecutionResult waitForResult(PlannerCompactionService service) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			CompactionExecutionResult result = service.poll();
			if (result != null) {
				return result;
			}
			Thread.sleep(10L);
		}
		return null;
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(HttpServer server) {
			this.server = server;
		}

		private static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.start();
			return new TestServer(server);
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
