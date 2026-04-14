package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleVisionBackendTest {
	@Test
	void describeBuildsMultimodalChatRequestAndParsesTextResponse() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleVisionBackend backend = new OpenAiCompatibleVisionBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:1",
				"planner-key",
				"planner-model",
				"http://127.0.0.1:" + server.port(),
				"vision-key",
				"gpt-4.1-mini",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			VisionDescription description = backend.describe(new VisionRequest(
				"Describe the current view.",
				"image/png",
				new byte[]{1, 2, 3},
				1234L
			));

			assertEquals("A birch forest under open sky.", description.text());
			assertEquals("gpt-4.1-mini", description.model());
			assertEquals(1234L, description.capturedAtMs());

			String body = bodyRef.get();
			assertTrue(body.contains("\"model\":\"gpt-4.1-mini\""));
			assertTrue(body.contains("\"type\":\"image_url\""));
			assertTrue(body.contains("\"detail\":\"low\""));
			assertTrue(body.contains("data:image/png;base64,AQID"));
			assertTrue(body.contains("Describe the current view."));
		}
	}

	@Test
	void describeIgnoresThoughtPartsAndReturnsVisibleText() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "content": [
			          {
			            "type": "reasoning",
			            "text": "Need to inspect the screenshot.",
			            "thought": true
			          },
			          {
			            "type": "text",
			            "text": "A birch forest under open sky."
			          }
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleVisionBackend backend = new OpenAiCompatibleVisionBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:1",
				"planner-key",
				"planner-model",
				"http://127.0.0.1:" + server.port(),
				"vision-key",
				"gpt-4.1-mini",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			VisionDescription description = backend.describe(new VisionRequest(
				"Describe the current view.",
				"image/png",
				new byte[]{1, 2, 3},
				1234L
			));

			assertEquals("A birch forest under open sky.", description.text());
		}
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(HttpServer server) {
			this.server = server;
		}

		private static TestServer start(AtomicReference<String> bodyRef) throws IOException {
			return start(bodyRef, """
				{
				  "choices": [
				    {
				      "message": {
				        "content": "A birch forest under open sky."
				      }
				    }
				  ]
				}
				""");
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
