package ai.moeru.airicraft.bridge;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BridgeRouteContext {
	private final HttpExchange exchange;
	private final Gson gson;
	private final Supplier<EmbodiedAgentRuntime> agentRuntimeSupplier;
	private final Function<Supplier<?>, Object> clientThreadInvoker;

	public BridgeRouteContext(
		HttpExchange exchange,
		Gson gson,
		Supplier<EmbodiedAgentRuntime> agentRuntimeSupplier,
		Function<Supplier<?>, Object> clientThreadInvoker
	) {
		this.exchange = Objects.requireNonNull(exchange, "exchange");
		this.gson = Objects.requireNonNull(gson, "gson");
		this.agentRuntimeSupplier = Objects.requireNonNull(agentRuntimeSupplier, "agentRuntimeSupplier");
		this.clientThreadInvoker = Objects.requireNonNull(clientThreadInvoker, "clientThreadInvoker");
	}

	public String method() {
		return exchange.getRequestMethod();
	}

	public boolean isMethod(String method) {
		return method != null && method.equalsIgnoreCase(method());
	}

	public String query(String key) {
		String rawQuery = exchange.getRequestURI().getRawQuery();
		if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) {
			return null;
		}
		for (String part : rawQuery.split("&")) {
			int split = part.indexOf('=');
			String currentKey = decode(split >= 0 ? part.substring(0, split) : part);
			if (!key.equals(currentKey)) {
				continue;
			}
			return split >= 0 ? decode(part.substring(split + 1)) : "";
		}
		return null;
	}

	public <T> T readJson(Class<T> requestType) throws IOException, JsonSyntaxException {
		try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			return gson.fromJson(reader, requestType);
		}
	}

	public void writeJson(int statusCode, Object body) throws IOException {
		byte[] response = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, response.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(response);
		}
	}

	public EmbodiedAgentRuntime agentRuntime() {
		return agentRuntimeSupplier.get();
	}

	@SuppressWarnings("unchecked")
	public <T> T onClientThread(Supplier<T> supplier) {
		return (T) clientThreadInvoker.apply(supplier);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
