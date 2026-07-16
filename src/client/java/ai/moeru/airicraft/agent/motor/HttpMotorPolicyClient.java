package ai.moeru.airicraft.agent.motor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Strict, retry-free HTTP transport for an Optimus-3 shadow policy service. */
public final class HttpMotorPolicyClient implements MotorPolicyClient {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int MAX_RESPONSE_CHARS = 1_000_000;
	private static final String SESSION_PATH = "/v1/policy/sessions";

	private static final Set<String> COMMON_KEYS = Set.of(
		"contractVersion", "mode", "sessionId", "generation",
		"executionId", "graphActionId", "graphStepId", "graphPrimitive", "stepAttempt", "taskId", "taskType",
		"prompt", "seed", "frameShape", "policyActionKeys",
		"modelId", "modelRevision", "actionHeadId", "actionHeadRevision", "policyContract",
		"expectedLabel", "taskEmbeddingSha256", "projectedEmbeddingSha256", "actuationAuthorized"
	);
	private static final Set<String> STEP_REQUEST_KEYS = plus(COMMON_KEYS,
		"stepIndex", "minecraftTick", "frameId", "capturedAtMs", "encodedFrameSha256", "decodedPixelsSha256", "framePngBase64");
	private static final Set<String> STEP_RESPONSE_KEYS = plus(COMMON_KEYS,
		"stepIndex", "minecraftTick", "frameId", "capturedAtMs", "encodedFrameSha256", "decodedPixelsSha256", "serviceTiming", "action");
	private static final Set<String> CLOSE_RESPONSE_KEYS = plus(COMMON_KEYS, "closed");
	private static final Set<String> SESSION_RESPONSE_KEYS = plus(COMMON_KEYS, "recurrentResetCount");
	private static final Set<String> TIMING_KEYS = Set.of("queueMs", "inferenceMs", "totalMs");

	private final URI baseUri;
	private final Duration createRequestTimeout;
	private final Duration stepRequestTimeout;
	private final Duration closeRequestTimeout;
	private final HttpClient httpClient;
	private final String bearerToken;
	private final String modalKey;
	private final String modalSecret;

	public HttpMotorPolicyClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
		this(baseUri, connectTimeout, requestTimeout, requestTimeout, requestTimeout, null, null, null);
	}

	public HttpMotorPolicyClient(
		URI baseUri,
		Duration connectTimeout,
		Duration createRequestTimeout,
		Duration stepRequestTimeout
	) {
		this(baseUri, connectTimeout, createRequestTimeout, stepRequestTimeout, createRequestTimeout, null, null, null);
	}

	public HttpMotorPolicyClient(
		URI baseUri,
		Duration connectTimeout,
		Duration createRequestTimeout,
		Duration stepRequestTimeout,
		Duration closeRequestTimeout
	) {
		this(baseUri, connectTimeout, createRequestTimeout, stepRequestTimeout, closeRequestTimeout, null, null, null);
	}

	public HttpMotorPolicyClient(
		URI baseUri,
		Duration connectTimeout,
		Duration requestTimeout,
		String bearerToken,
		String modalKey,
		String modalSecret
	) {
		this(baseUri, connectTimeout, requestTimeout, requestTimeout, requestTimeout, bearerToken, modalKey, modalSecret);
	}

	public HttpMotorPolicyClient(
		URI baseUri,
		Duration connectTimeout,
		Duration createRequestTimeout,
		Duration stepRequestTimeout,
		String bearerToken,
		String modalKey,
		String modalSecret
	) {
		this(baseUri, connectTimeout, createRequestTimeout, stepRequestTimeout, createRequestTimeout, bearerToken, modalKey, modalSecret);
	}

	public HttpMotorPolicyClient(
		URI baseUri,
		Duration connectTimeout,
		Duration createRequestTimeout,
		Duration stepRequestTimeout,
		Duration closeRequestTimeout,
		String bearerToken,
		String modalKey,
		String modalSecret
	) {
		this.baseUri = validateBaseUri(baseUri);
		Duration safeConnectTimeout = positive(connectTimeout, "connectTimeout");
		this.createRequestTimeout = positive(createRequestTimeout, "createRequestTimeout");
		this.stepRequestTimeout = positive(stepRequestTimeout, "stepRequestTimeout");
		this.closeRequestTimeout = positive(closeRequestTimeout, "closeRequestTimeout");
		this.bearerToken = optionalCredential(bearerToken);
		this.modalKey = optionalCredential(modalKey);
		this.modalSecret = optionalCredential(modalSecret);
		if ((this.modalKey == null) != (this.modalSecret == null)) {
			throw new IllegalArgumentException("Modal-Key and Modal-Secret must be configured together");
		}
		if (this.modalKey != null && !isModalProxyEndpoint(this.baseUri)) {
			throw new IllegalArgumentException(
				"Modal proxy credentials require an https://*.modal.run or loopback endpoint"
			);
		}
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(safeConnectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public CompletableFuture<MotorPolicySession> createSession(MotorSessionCreateRequest request) {
		Objects.requireNonNull(request, "request");
		JsonObject body = commonJson(request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt());
		return post(endpoint(SESSION_PATH), body, createRequestTimeout)
			.thenApply(response -> parseSession(response, request));
	}

	@Override
	public CompletableFuture<MotorPolicyStepResult> step(MotorPolicyStepRequest request) {
		Objects.requireNonNull(request, "request");
		JsonObject body = commonJson(request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt());
		body.addProperty("stepIndex", request.stepIndex());
		body.addProperty("minecraftTick", request.minecraftTick());
		body.addProperty("frameId", request.frame().frameId());
		body.addProperty("capturedAtMs", request.frame().capturedAtMs());
		body.addProperty("encodedFrameSha256", request.frame().encodedSha256());
		body.addProperty("decodedPixelsSha256", request.frame().decodedRgbSha256());
		body.addProperty("framePngBase64", Base64.getEncoder().encodeToString(request.frame().pngBytes()));
		requireExactKeys(body, STEP_REQUEST_KEYS, "step request");
		return post(endpoint(SESSION_PATH + "/" + request.sessionId() + "/steps"), body, stepRequestTimeout)
			.thenApply(response -> parseStep(response, request));
	}

	@Override
	public CompletableFuture<Void> closeSession(MotorSessionCloseRequest request) {
		Objects.requireNonNull(request, "request");
		MotorPolicySession session = request.session();
		JsonObject body = commonJson(session.identity(), session.sessionId(), session.generation(), session.seed(), session.prompt());
		return post(endpoint(SESSION_PATH + "/" + session.sessionId() + "/close"), body, closeRequestTimeout)
			.thenApply(response -> {
				JsonObject object = parseObject(response);
				requireExactKeys(object, CLOSE_RESPONSE_KEYS, "close response");
				validateCommon(object, session.identity(), session.sessionId(), session.generation(), session.seed(), session.prompt());
				requireBoolean(object, "closed", true);
				return null;
			});
	}

	@Override
	public String toString() {
		return "HttpMotorPolicyClient[baseUri=" + baseUri + ", authentication=<redacted>]";
	}

	private CompletableFuture<String> post(URI uri, JsonObject body, Duration timeout) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json");
		if (bearerToken != null) {
			builder.header("Authorization", "Bearer " + bearerToken);
		}
		if (modalKey != null) {
			builder.header("Modal-Key", modalKey);
			builder.header("Modal-Secret", modalSecret);
		}
		try {
			HttpRequest request = builder
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
			return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.handle((response, failure) -> {
					if (failure != null) {
						Throwable cause = unwrapCompletion(failure);
						throw new CompletionException(new MotorPolicyException(
							cause instanceof HttpTimeoutException ? "timeout" : "transport_failure",
							cause instanceof HttpTimeoutException
								? "Motor policy request timed out"
								: "Motor policy request failed"
						));
					}
					if (response.statusCode() < 200 || response.statusCode() >= 300) {
						throw new CompletionException(new MotorPolicyException(
							"http_status",
							"Motor policy service returned HTTP " + response.statusCode()
						));
					}
					String responseBody = response.body();
					if (responseBody == null || responseBody.length() > MAX_RESPONSE_CHARS) {
						throw new CompletionException(new MotorPolicyException("invalid_response", "Motor policy response size is invalid"));
					}
					return responseBody;
				});
		}
		catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(new MotorPolicyException(
				"dispatch_failure",
				"Motor policy request could not be dispatched"
			));
		}
	}

	private static MotorPolicySession parseSession(String response, MotorSessionCreateRequest request) {
		JsonObject object = parseObject(response);
		requireExactKeys(object, SESSION_RESPONSE_KEYS, "session response");
		validateCommon(object, request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt());
		requireLong(object, "recurrentResetCount", 1L);
		return new MotorPolicySession(
			request.identity(),
			request.sessionId(),
			request.generation(),
			request.seed(),
			request.prompt(),
			1,
			Instant.now()
		);
	}

	private static MotorPolicyStepResult parseStep(String response, MotorPolicyStepRequest request) {
		JsonObject object = parseObject(response);
		requireExactKeys(object, STEP_RESPONSE_KEYS, "step response");
		validateCommon(object, request.identity(), request.sessionId(), request.generation(), request.seed(), request.prompt());
		requireLong(object, "stepIndex", request.stepIndex());
		requireLong(object, "minecraftTick", request.minecraftTick());
		requireLong(object, "frameId", request.frame().frameId());
		requireLong(object, "capturedAtMs", request.frame().capturedAtMs());
		requireString(object, "encodedFrameSha256", request.frame().encodedSha256());
		requireString(object, "decodedPixelsSha256", request.frame().decodedRgbSha256());

		JsonObject timing = requireObject(object, "serviceTiming");
		requireExactKeys(timing, TIMING_KEYS, "service timing");
		MotorPolicyServiceTiming serviceTiming;
		try {
			serviceTiming = new MotorPolicyServiceTiming(
				requireFiniteNumber(timing, "queueMs"),
				requireFiniteNumber(timing, "inferenceMs"),
				requireFiniteNumber(timing, "totalMs")
			);
		}
		catch (IllegalArgumentException exception) {
			throw MotorPolicyException.schema("service timing values are invalid");
		}
		OptimusPolicyAction rawAction = OptimusPolicyAction.fromJson(object.get("action"));
		return new MotorPolicyStepResult(
			request.identity(),
			request.sessionId(),
			request.generation(),
			request.stepIndex(),
			request.seed(),
			request.prompt(),
			request.minecraftTick(),
			request.frame().frameId(),
			request.frame().capturedAtMs(),
			request.frame().encodedSha256(),
			request.frame().decodedRgbSha256(),
			false,
			MotorPolicyEvidence.from(rawAction),
			serviceTiming,
			Math.max(0L, System.nanoTime() - request.frame().captureStartedNanos()),
			Instant.now()
		);
	}

	private static JsonObject commonJson(
		MotorGraphIdentity identity,
		String sessionId,
		long generation,
		long seed,
		String prompt
	) {
		JsonObject object = new JsonObject();
		object.addProperty("contractVersion", MotorPolicyContract.CONTRACT_VERSION);
		object.addProperty("mode", MotorPolicyContract.MODE);
		object.addProperty("sessionId", sessionId);
		object.addProperty("generation", generation);
		object.addProperty("executionId", identity.executionId());
		object.addProperty("graphActionId", identity.graphActionId());
		object.addProperty("graphStepId", identity.graphStepId());
		object.addProperty("graphPrimitive", identity.graphPrimitive());
		object.addProperty("stepAttempt", identity.stepAttempt());
		object.addProperty("taskId", identity.taskId());
		object.addProperty("taskType", identity.taskType());
		object.addProperty("prompt", prompt);
		object.addProperty("seed", seed);
		object.add("frameShape", integerArray(MotorPolicyContract.FRAME_WIDTH, MotorPolicyContract.FRAME_HEIGHT, MotorPolicyContract.FRAME_CHANNELS));
		object.add("policyActionKeys", stringArray(MotorPolicyContract.POLICY_ACTION_KEYS));
		object.addProperty("modelId", MotorPolicyContract.MODEL_ID);
		object.addProperty("modelRevision", MotorPolicyContract.MODEL_REVISION);
		object.addProperty("actionHeadId", MotorPolicyContract.ACTION_HEAD_ID);
		object.addProperty("actionHeadRevision", MotorPolicyContract.ACTION_HEAD_REVISION);
		object.addProperty("policyContract", MotorPolicyContract.POLICY_CONTRACT);
		object.addProperty("expectedLabel", MotorPolicyContract.EXPECTED_LABEL);
		object.addProperty("taskEmbeddingSha256", MotorPolicyContract.TASK_EMBEDDING_SHA256);
		object.addProperty("projectedEmbeddingSha256", MotorPolicyContract.PROJECTED_EMBEDDING_SHA256);
		object.addProperty("actuationAuthorized", false);
		return object;
	}

	private static void validateCommon(
		JsonObject object,
		MotorGraphIdentity identity,
		String sessionId,
		long generation,
		long seed,
		String prompt
	) {
		requireString(object, "contractVersion", MotorPolicyContract.CONTRACT_VERSION);
		requireString(object, "mode", MotorPolicyContract.MODE);
		requireString(object, "sessionId", sessionId);
		requireLong(object, "generation", generation);
		requireString(object, "executionId", identity.executionId());
		requireString(object, "graphActionId", identity.graphActionId());
		requireString(object, "graphStepId", identity.graphStepId());
		requireString(object, "graphPrimitive", identity.graphPrimitive());
		requireLong(object, "stepAttempt", identity.stepAttempt());
		requireString(object, "taskId", identity.taskId());
		requireString(object, "taskType", identity.taskType());
		requireString(object, "prompt", prompt);
		requireLong(object, "seed", seed);
		requireIntegerArray(object, "frameShape", List.of(128, 128, 3));
		requireStringArray(object, "policyActionKeys", MotorPolicyContract.POLICY_ACTION_KEYS);
		requireString(object, "modelId", MotorPolicyContract.MODEL_ID);
		requireString(object, "modelRevision", MotorPolicyContract.MODEL_REVISION);
		requireString(object, "actionHeadId", MotorPolicyContract.ACTION_HEAD_ID);
		requireString(object, "actionHeadRevision", MotorPolicyContract.ACTION_HEAD_REVISION);
		requireString(object, "policyContract", MotorPolicyContract.POLICY_CONTRACT);
		requireString(object, "expectedLabel", MotorPolicyContract.EXPECTED_LABEL);
		requireString(object, "taskEmbeddingSha256", MotorPolicyContract.TASK_EMBEDDING_SHA256);
		requireString(object, "projectedEmbeddingSha256", MotorPolicyContract.PROJECTED_EMBEDDING_SHA256);
		requireBoolean(object, "actuationAuthorized", false);
	}

	private static JsonObject parseObject(String body) {
		try {
			JsonElement element = JsonParser.parseString(body);
			if (!element.isJsonObject()) {
				throw MotorPolicyException.schema("response must be a JSON object");
			}
			return element.getAsJsonObject();
		}
		catch (MotorPolicyException exception) {
			throw exception;
		}
		catch (JsonParseException | IllegalStateException exception) {
			throw MotorPolicyException.schema("response is not valid JSON");
		}
	}

	private static void requireExactKeys(JsonObject object, Set<String> expected, String objectName) {
		if (!object.keySet().equals(expected)) {
			throw MotorPolicyException.schema(objectName + " fields do not match the frozen contract");
		}
	}

	private static void requireString(JsonObject object, String key, String expected) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
			|| !expected.equals(element.getAsString())) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
	}

	private static void requireLong(JsonObject object, String key, long expected) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
		try {
			if (new BigDecimal(element.getAsString()).longValueExact() != expected) {
				throw MotorPolicyException.schema("response echo mismatch: " + key);
			}
		}
		catch (ArithmeticException | NumberFormatException exception) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
	}

	private static void requireBoolean(JsonObject object, String key, boolean expected) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()
			|| element.getAsBoolean() != expected) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
	}

	private static JsonObject requireObject(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonObject()) {
			throw MotorPolicyException.schema(key + " must be an object");
		}
		return element.getAsJsonObject();
	}

	private static double requireFiniteNumber(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw MotorPolicyException.schema(key + " must be a finite number");
		}
		double value;
		try {
			value = element.getAsDouble();
		}
		catch (NumberFormatException exception) {
			throw MotorPolicyException.schema(key + " must be a finite number");
		}
		if (!Double.isFinite(value)) {
			throw MotorPolicyException.schema(key + " must be a finite number");
		}
		return value;
	}

	private static void requireIntegerArray(JsonObject object, String key, List<Integer> expected) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != expected.size()) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
		for (int index = 0; index < expected.size(); index++) {
			JsonObject wrapper = new JsonObject();
			wrapper.add("value", element.getAsJsonArray().get(index));
			requireLong(wrapper, "value", expected.get(index));
		}
	}

	private static void requireStringArray(JsonObject object, String key, List<String> expected) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != expected.size()) {
			throw MotorPolicyException.schema("response echo mismatch: " + key);
		}
		for (int index = 0; index < expected.size(); index++) {
			JsonElement value = element.getAsJsonArray().get(index);
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
				|| !expected.get(index).equals(value.getAsString())) {
				throw MotorPolicyException.schema("response echo mismatch: " + key);
			}
		}
	}

	private URI endpoint(String suffix) {
		String base = baseUri.toString();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return URI.create(base + suffix);
	}

	private static URI validateBaseUri(URI uri) {
		Objects.requireNonNull(uri, "baseUri");
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost();
		if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("baseUri must be an absolute credential-free HTTP endpoint");
		}
		if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopbackHost(host))) {
			throw new IllegalArgumentException("baseUri must use HTTPS or loopback HTTP");
		}
		return uri;
	}

	private static boolean isLoopbackHost(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		if ("localhost".equals(normalized) || "[::1]".equals(normalized) || "::1".equals(normalized)
			|| "0:0:0:0:0:0:0:1".equals(normalized)) {
			return true;
		}
		String[] octets = normalized.split("\\.");
		if (octets.length != 4 || !"127".equals(octets[0])) {
			return false;
		}
		try {
			for (String octet : octets) {
				int value = Integer.parseInt(octet);
				if (value < 0 || value > 255) {
					return false;
				}
			}
			return true;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	private static boolean isModalProxyEndpoint(URI uri) {
		String host = uri.getHost();
		return isLoopbackHost(host)
			|| ("https".equalsIgnoreCase(uri.getScheme())
				&& host.toLowerCase(Locale.ROOT).endsWith(".modal.run"));
	}

	private static Duration positive(Duration duration, String name) {
		Objects.requireNonNull(duration, name);
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return duration;
	}

	private static String optionalCredential(String credential) {
		return credential == null || credential.isBlank() ? null : credential;
	}

	private static Throwable unwrapCompletion(Throwable failure) {
		Throwable current = failure;
		while (current instanceof CompletionException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static JsonArray integerArray(int... values) {
		JsonArray array = new JsonArray();
		for (int value : values) {
			array.add(value);
		}
		return array;
	}

	private static JsonArray stringArray(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static Set<String> plus(Set<String> base, String... extra) {
		LinkedHashSet<String> values = new LinkedHashSet<>(base);
		values.addAll(List.of(extra));
		return Set.copyOf(values);
	}

}
