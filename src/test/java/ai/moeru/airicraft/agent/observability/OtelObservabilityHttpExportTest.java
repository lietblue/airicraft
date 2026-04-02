package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test
	void weaveCaptureUsesCallsCompleteAndSuppressesLegacyOtlpCaptureSpan() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch completeRequestSeen = new CountDownLatch(1);
		AtomicReference<CapturedRequest> completeCaptured = new AtomicReference<>();
		AtomicInteger otlpRequestCount = new AtomicInteger();
		server.createContext("/otel/v1/traces", exchange -> {
			otlpRequestCount.incrementAndGet();
			byte[] response = new byte[0];
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(response);
			}
		});
		server.createContext("/v2/shinohara-rin/airicraft/calls/complete", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			completeCaptured.set(new CapturedRequest(
				exchange.getRequestURI().getPath(),
				Map.copyOf(exchange.getRequestHeaders()),
				body
			));
			byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(response);
			}
			completeRequestSeen.countDown();
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
				true
			);
			AgentObservability observability = AgentObservability.create(config);
			Context turnContext = observability.startTurnSpan(null, "session:test:speaker=rin");
			Context captureContext = observability.startChildSpan(AgentObservability.TOOL_CAPTURE_SPAN_NAME, turnContext);
			observability.recordImageCapture(
				captureContext,
				new FirstPersonScreenshotService.CapturedScreenshot(
					"png",
					854,
					480,
					1920,
					1080,
					1234L,
					new byte[]{1, 2, 3}
				)
			);
			observability.endSpan(captureContext);
			observability.endSpan(turnContext);
			observability.shutdown();

			assertTrue(completeRequestSeen.await(5, TimeUnit.SECONDS), "expected a Weave calls/complete request");
			CapturedRequest request = completeCaptured.get();
			assertNotNull(request);
			assertEquals("/v2/shinohara-rin/airicraft/calls/complete", request.path());
			assertTrue(
				firstHeaderValue(request.headers(), "authorization").startsWith("Basic "),
				"expected weave capture sidecar to add HTTP Basic authorization"
			);
			String bodyText = new String(request.body(), StandardCharsets.UTF_8);
			assertTrue(bodyText.contains(AgentObservability.TOOL_CAPTURE_SPAN_NAME));
			assertTrue(bodyText.contains("\"project_id\":\"shinohara-rin/airicraft\""));
			assertTrue(bodyText.contains("\"wandb.thread_id\":\"session:test:speaker=rin\""));
			assertTrue(bodyText.contains("\"output\":\"data:image/png;base64,AQID\""));
			assertEquals(0, otlpRequestCount.get(), "expected capture span to bypass legacy OTLP export");
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
