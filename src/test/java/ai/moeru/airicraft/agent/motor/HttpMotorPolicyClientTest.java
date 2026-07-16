package ai.moeru.airicraft.agent.motor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpMotorPolicyClientTest {
	@Test
	void refusesToSendModalProxyCredentialsToArbitraryHttpsHosts() {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
			new HttpMotorPolicyClient(
				URI.create("https://example.test"),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				"",
				"wk-test",
				"ws-test"
			)
		);

		assertEquals(
			"Modal proxy credentials require an https://*.modal.run or loopback endpoint",
			failure.getMessage()
		);
	}

	@Test
	void runsStrictSessionStepCloseProtocolAndRetainsShadowEvidence() throws Exception {
		List<String> paths = new ArrayList<>();
		AtomicBoolean authValid = new AtomicBoolean(true);
		try (TestServer server = new TestServer((exchange, request) -> {
			paths.add(exchange.getRequestURI().getPath());
			authValid.compareAndSet(true, "Bearer bearer-value".equals(exchange.getRequestHeaders().getFirst("Authorization")));
			authValid.compareAndSet(true, "modal-key".equals(exchange.getRequestHeaders().getFirst("Modal-Key")));
			authValid.compareAndSet(true, "modal-secret".equals(exchange.getRequestHeaders().getFirst("Modal-Secret")));
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/v1/policy/sessions")) {
				request.addProperty("recurrentResetCount", 1);
			}
			else if (path.endsWith("/steps")) {
				successfulStepResponse(request, true);
			}
			else if (path.endsWith("/close")) {
				request.addProperty("closed", true);
			}
			return request;
		})) {
			HttpMotorPolicyClient client = new HttpMotorPolicyClient(
				server.baseUri(), Duration.ofSeconds(1), Duration.ofSeconds(1),
				"bearer-value", "modal-key", "modal-secret"
			);
			MotorGraphIdentity identity = identity();
			MotorSessionCreateRequest createRequest = MotorSessionCreateRequest.create(identity, "session-test", 3, 17);
			MotorPolicySession session = client.createSession(createRequest).join();
			assertEquals(1, session.recurrentResetCount());
			MotorPolicyStepRequest stepRequest = new MotorPolicyStepRequest(
				identity, session.sessionId(), session.generation(), 0, session.seed(), session.prompt(), 42,
				frame(), false
			);
			MotorPolicyStepResult result = client.step(stepRequest).join();
			client.closeSession(new MotorSessionCloseRequest(session)).join();

			assertEquals(List.of(
				"/v1/policy/sessions",
				"/v1/policy/sessions/session-test/steps",
				"/v1/policy/sessions/session-test/close"
			), paths);
			assertTrue(authValid.get());
			assertFalse(result.actuationAuthorized());
			assertEquals(List.of("use"), result.evidence().forbiddenAttempts());
			assertEquals(List.of("left"), result.evidence().attackStabilizedControls());
			assertEquals(1, result.evidence().rawAction().use());
			assertEquals(0, result.evidence().safeShadowAction().use());
			assertFalse(client.toString().contains("bearer-value"));
			assertFalse(client.toString().contains("modal-secret"));
		}
	}

	@Test
	void httpCreateMayOutliveStepBudgetWhileStepStillTimesOutQuickly() throws Exception {
		try (TestServer server = new TestServer((exchange, request) -> {
			pause(120L);
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/v1/policy/sessions")) {
				request.addProperty("recurrentResetCount", 1);
			}
			else if (path.endsWith("/steps")) {
				successfulStepResponse(request, false);
			}
			return request;
		})) {
			HttpMotorPolicyClient client = new HttpMotorPolicyClient(
				server.baseUri(),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				Duration.ofMillis(30)
			);
			MotorGraphIdentity identity = identity();
			MotorPolicySession session = client.createSession(
				MotorSessionCreateRequest.create(identity, "session-timeouts", 1, 0)
			).join();
			MotorPolicyStepRequest step = new MotorPolicyStepRequest(
				identity, session.sessionId(), session.generation(), 0, session.seed(), session.prompt(), 42, frame(), false
			);
			CompletionException failure = assertThrows(CompletionException.class, () -> client.step(step).join());
			MotorPolicyException policyFailure = assertInstanceOf(MotorPolicyException.class, failure.getCause());
			assertEquals("timeout", policyFailure.code());
		}
	}

	@Test
	void closeUsesItsOwnBoundedTimeout() throws Exception {
		try (TestServer server = new TestServer((exchange, request) -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/v1/policy/sessions")) {
				request.addProperty("recurrentResetCount", 1);
			}
			else if (path.endsWith("/close")) {
				pause(120L);
				request.addProperty("closed", true);
			}
			return request;
		})) {
			HttpMotorPolicyClient client = new HttpMotorPolicyClient(
				server.baseUri(),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				Duration.ofMillis(30)
			);
			MotorPolicySession session = client.createSession(
				MotorSessionCreateRequest.create(identity(), "session-close-timeout", 1, 0)
			).join();
			CompletionException failure = assertThrows(CompletionException.class, () ->
				client.closeSession(new MotorSessionCloseRequest(session)).join()
			);
			MotorPolicyException policyFailure = assertInstanceOf(MotorPolicyException.class, failure.getCause());
			assertEquals("timeout", policyFailure.code());
		}
	}

	@Test
	void rejectsResponseWithAnyExtraField() throws Exception {
		try (TestServer server = new TestServer((exchange, request) -> {
			request.addProperty("recurrentResetCount", 1);
			request.addProperty("unexpected", true);
			return request;
		})) {
			HttpMotorPolicyClient client = new HttpMotorPolicyClient(
				server.baseUri(), Duration.ofSeconds(1), Duration.ofSeconds(1)
			);
			CompletionException failure = assertThrows(CompletionException.class, () ->
				client.createSession(MotorSessionCreateRequest.create(identity(), "session-test", 1, 0)).join()
			);
			MotorPolicyException policyFailure = assertInstanceOf(MotorPolicyException.class, failure.getCause());
			assertEquals("invalid_response_schema", policyFailure.code());
		}
	}

	@Test
	void permitsRemoteHttpsButOnlyLoopbackPlainHttpAndRequiresModalCredentialPair() {
		assertThrows(IllegalArgumentException.class, () -> new HttpMotorPolicyClient(
			URI.create("http://example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1)
		));
		new HttpMotorPolicyClient(
			URI.create("https://example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1)
		);
		assertThrows(IllegalArgumentException.class, () -> new HttpMotorPolicyClient(
			URI.create("https://example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1),
			null, "only-key", null
		));
	}

	private static MotorGraphIdentity identity() {
		return new MotorGraphIdentity(
			"execution", "action", "step", "mine_block", 0, "task", "BREAK_BLOCKS"
		);
	}

	private static MotorFrame frame() {
		return new MotorFrame(
			1, 1_000, 10_000, MotorFramePreprocessor.FORMAT, 128, 128, 854, 480,
			MotorFramePreprocessor.TRANSFORM, "a".repeat(64), "b".repeat(64), new byte[]{1, 2, 3}
		);
	}

	private static void successfulStepResponse(JsonObject request, boolean includeActiveEvidence) {
		request.remove("framePngBase64");
		JsonObject timing = new JsonObject();
		timing.addProperty("queueMs", 0.25);
		timing.addProperty("inferenceMs", 8.5);
		timing.addProperty("totalMs", 9.0);
		request.add("serviceTiming", timing);
		JsonObject action = MotorShadowRuntimeTest.noopAction().toJson();
		if (includeActiveEvidence) {
			action.addProperty("attack", 1);
			action.addProperty("left", 1);
			action.addProperty("use", 1);
		}
		request.add("action", action);
	}

	private static void pause(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	private interface Responder {
		JsonObject respond(HttpExchange exchange, JsonObject request);
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(Responder responder) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> handle(exchange, responder));
			server.start();
		}

		private URI baseUri() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		}

		@Override
		public void close() {
			server.stop(0);
		}

		private static void handle(HttpExchange exchange, Responder responder) throws IOException {
			try (exchange) {
				byte[] requestBytes = exchange.getRequestBody().readAllBytes();
				JsonObject request = JsonParser.parseString(new String(requestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
				byte[] response = responder.respond(exchange, request).toString().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
		}
	}
}
