package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.agent.AgentConfig;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelObservabilityHttpExportTest {
	@Test
	void weaveProfileExportsExpectedHeadersAndResourceAttributes() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch requestSeen = new CountDownLatch(1);
		AtomicReference<CapturedRequest> captured = new AtomicReference<>();
		server.createContext("/otel/v1/traces", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			captured.set(new CapturedRequest(
				exchange.getRequestURI().getPath(),
				Map.copyOf(exchange.getRequestHeaders()),
				body
			));
			byte[] response = new byte[0];
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(response);
			}
			requestSeen.countDown();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			AgentConfig.ObservabilityConfig config = new AgentConfig.ObservabilityConfig(
				true,
				"otlp_http",
				"http://127.0.0.1:" + port + "/otel/v1/traces",
				Map.of("wandb-api-key", "test-key"),
				Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				),
				"weave",
				false,
				false,
				false,
				false
			);
			AgentObservability observability = AgentObservability.create(config);
			Context context = observability.startChildSpan(AgentObservability.PLANNER_REQUEST_SPAN_NAME, Context.root());
			observability.setSpanAttribute(context, "airicraft.thread_id", "session:none:player:debug");
			observability.endSpan(context);
			observability.shutdown();

			assertTrue(requestSeen.await(5, TimeUnit.SECONDS), "expected an OTLP HTTP request");
			CapturedRequest request = captured.get();
			assertNotNull(request);
			assertEquals("/otel/v1/traces", request.path());
			assertHeaderEquals(request.headers(), "wandb-api-key", "test-key");
			assertHeaderEquals(request.headers(), "project_id", "shinohara-rin/airicraft");
			assertTrue(
				firstHeaderValue(request.headers(), "authorization").startsWith("Basic "),
				"expected weave profile to add HTTP Basic authorization"
			);

			String bodyText = new String(request.body(), StandardCharsets.ISO_8859_1);
			assertTrue(bodyText.contains("wandb.entity"));
			assertTrue(bodyText.contains("shinohara-rin"));
			assertTrue(bodyText.contains("wandb.project"));
			assertTrue(bodyText.contains("airicraft"));
			assertTrue(bodyText.contains("wb_entity"));
			assertTrue(bodyText.contains("wb_project"));
			assertTrue(bodyText.contains("service.name"));
			assertTrue(bodyText.contains("airicraft-mod"));
			assertTrue(bodyText.contains("wandb.thread_id"));
		}
		finally {
			server.stop(0);
		}
	}

	private static void assertHeaderEquals(Map<String, List<String>> headers, String name, String expectedValue) {
		assertEquals(expectedValue, firstHeaderValue(headers, name));
	}

	private static String firstHeaderValue(Map<String, List<String>> headers, String name) {
		return headers.entrySet().stream()
			.filter(entry -> entry.getKey().equalsIgnoreCase(name))
			.map(Map.Entry::getValue)
			.filter(values -> values != null && !values.isEmpty())
			.map(values -> values.getFirst())
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing header: " + name));
	}

	private record CapturedRequest(String path, Map<String, List<String>> headers, byte[] body) {
	}
}
