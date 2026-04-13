package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentConfigLoader {
	private static final Gson GSON = new Gson();
	private static final Yaml YAML = createYaml();
	private static final String TEMPLATE_RESOURCE = "/config/airicraft/agent.yml.example";
	private static final String TEMPLATE_FILENAME = "agent.yml.example";
	private static final String CONFIG_FILENAME = "agent.yml";
	private static final String LEGACY_CONFIG_FILENAME = "agent.json";

	private AgentConfigLoader() {
	}

	public static AgentConfig load() {
		AgentConfig defaults = AgentConfig.defaults();
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
		Path templatePath = configDir.resolve(TEMPLATE_FILENAME);
		Path configPath = configDir.resolve(CONFIG_FILENAME);
		Path legacyConfigPath = configDir.resolve(LEGACY_CONFIG_FILENAME);

		try {
			Files.createDirectories(configDir);
			ensureFile(templatePath);
			if (Files.notExists(configPath)) {
				if (Files.exists(legacyConfigPath)) {
					migrateLegacyJsonConfig(legacyConfigPath, configPath, defaults);
				}
				else {
					Files.copy(templatePath, configPath);
				}
			}

			try (Reader fileReader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
				return fromMap(parseYaml(fileReader), defaults);
			}
		}
		catch (IOException | JsonParseException exception) {
			Airicraft.LOGGER.warn("Failed to load Airicraft agent config; using defaults", exception);
			return defaults;
		}
	}

	static AgentConfig fromMap(Map<String, Object> root, AgentConfig defaults) {
		AgentConfig.LlmConfig llm = new AgentConfig.LlmConfig(
			readString(root, "providerBaseUrl", defaults.llm().providerBaseUrl()),
			readString(root, "apiKey", defaults.llm().apiKey()),
			readString(root, "model", defaults.llm().model()),
			readString(root, "visionProviderBaseUrl", defaults.llm().visionProviderBaseUrl()),
			readString(root, "visionApiKey", defaults.llm().visionApiKey()),
			readString(root, "visionModel", defaults.llm().visionModel()),
			readInt(root, "requestTimeoutMillis", defaults.llm().requestTimeoutMillis()),
			readInt(root, "visionRequestTimeoutMillis", defaults.llm().visionRequestTimeoutMillis()),
			readInt(root, "maxRecentConversationTurns", defaults.llm().maxRecentConversationTurns()),
			readInt(root, "plannerCompactionTriggerTokens", defaults.llm().plannerCompactionTriggerTokens()),
			readInt(root, "plannerPendingSemanticEventCap", defaults.llm().plannerPendingSemanticEventCap()),
			readInt(root, "plannerSessionMaxConcurrentAttempts", defaults.llm().plannerSessionMaxConcurrentAttempts()),
			readInt(root, "plannerSessionCoalesceStepMillis", defaults.llm().plannerSessionCoalesceStepMillis()),
			readInt(root, "plannerSessionCoalesceMinMillis", defaults.llm().plannerSessionCoalesceMinMillis()),
			readInt(root, "plannerSessionCoalesceMaxMillis", defaults.llm().plannerSessionCoalesceMaxMillis()),
			readString(root, "visionImageDetail", defaults.llm().visionImageDetail()),
			readBoolean(root, "plannerNativeVisionEnabled", defaults.llm().plannerNativeVisionEnabled())
		);
		warnIfMalformedObject(root, "observability");
		Map<String, Object> observabilityRoot = readObjectMap(root, "observability");
		AgentConfig.ObservabilityConfig observability = new AgentConfig.ObservabilityConfig(
			readBoolean(observabilityRoot, "enabled", defaults.observability().enabled()),
			readString(observabilityRoot, "exporter", defaults.observability().exporter()),
			readString(observabilityRoot, "otlpEndpoint", defaults.observability().otlpEndpoint()),
			readStringMap(observabilityRoot, "otlpHeaders", defaults.observability().otlpHeaders()),
			readStringMap(observabilityRoot, "resourceAttributes", defaults.observability().resourceAttributes()),
			readString(observabilityRoot, "vendorProfile", defaults.observability().vendorProfile()),
			readBoolean(observabilityRoot, "debugLogExports", defaults.observability().debugLogExports()),
			readBoolean(observabilityRoot, "captureInputs", defaults.observability().captureInputs()),
			readBoolean(observabilityRoot, "captureOutputs", defaults.observability().captureOutputs()),
			readBoolean(observabilityRoot, "captureImages", defaults.observability().captureImages())
		);
		return new AgentConfig(defaults.verificationEnabled(), defaults.verificationAutoRunAll(), llm, observability);
	}

	private static void ensureFile(Path path) throws IOException {
		if (Files.exists(path)) {
			return;
		}

		try (InputStream stream = AgentConfigLoader.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing embedded agent config template: " + TEMPLATE_RESOURCE);
			}
			Files.copy(stream, path);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Reader reader) {
		Object loaded = YAML.load(reader);
		if (loaded instanceof Map<?, ?> map) {
			Map<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static void migrateLegacyJsonConfig(Path legacyConfigPath, Path yamlConfigPath, AgentConfig defaults) throws IOException {
		String json = Files.readString(legacyConfigPath, StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> root = GSON.fromJson(json, Map.class);
		if (root == null) {
			Files.copy(yamlConfigPath.getParent().resolve(TEMPLATE_FILENAME), yamlConfigPath);
			return;
		}

		Map<String, Object> yamlData = new LinkedHashMap<>();
		yamlData.put("providerBaseUrl", readString(root, "providerBaseUrl", defaults.llm().providerBaseUrl()));
		yamlData.put("apiKey", readString(root, "apiKey", defaults.llm().apiKey()));
		yamlData.put("model", readString(root, "model", defaults.llm().model()));
		yamlData.put("visionProviderBaseUrl", readString(root, "visionProviderBaseUrl", defaults.llm().visionProviderBaseUrl()));
		yamlData.put("visionApiKey", readString(root, "visionApiKey", defaults.llm().visionApiKey()));
		yamlData.put("visionModel", readString(root, "visionModel", defaults.llm().visionModel()));
		yamlData.put("requestTimeoutMillis", readInt(root, "requestTimeoutMillis", defaults.llm().requestTimeoutMillis()));
		yamlData.put("visionRequestTimeoutMillis", readInt(root, "visionRequestTimeoutMillis", defaults.llm().visionRequestTimeoutMillis()));
		yamlData.put("maxRecentConversationTurns", readInt(root, "maxRecentConversationTurns", defaults.llm().maxRecentConversationTurns()));
		yamlData.put("plannerCompactionTriggerTokens", readInt(root, "plannerCompactionTriggerTokens", defaults.llm().plannerCompactionTriggerTokens()));
		yamlData.put("plannerPendingSemanticEventCap", readInt(root, "plannerPendingSemanticEventCap", defaults.llm().plannerPendingSemanticEventCap()));
		yamlData.put("plannerSessionMaxConcurrentAttempts", readInt(root, "plannerSessionMaxConcurrentAttempts", defaults.llm().plannerSessionMaxConcurrentAttempts()));
		yamlData.put("plannerSessionCoalesceStepMillis", readInt(root, "plannerSessionCoalesceStepMillis", defaults.llm().plannerSessionCoalesceStepMillis()));
		yamlData.put("plannerSessionCoalesceMinMillis", readInt(root, "plannerSessionCoalesceMinMillis", defaults.llm().plannerSessionCoalesceMinMillis()));
		yamlData.put("plannerSessionCoalesceMaxMillis", readInt(root, "plannerSessionCoalesceMaxMillis", defaults.llm().plannerSessionCoalesceMaxMillis()));
		yamlData.put("visionImageDetail", readString(root, "visionImageDetail", defaults.llm().visionImageDetail()));
		yamlData.put("plannerNativeVisionEnabled", readBoolean(root, "plannerNativeVisionEnabled", defaults.llm().plannerNativeVisionEnabled()));
		yamlData.put("observability", Map.of(
			"enabled", defaults.observability().enabled(),
			"exporter", defaults.observability().exporter(),
			"otlpEndpoint", defaults.observability().otlpEndpoint(),
			"otlpHeaders", defaults.observability().otlpHeaders(),
			"resourceAttributes", defaults.observability().resourceAttributes(),
			"vendorProfile", defaults.observability().vendorProfile(),
			"debugLogExports", defaults.observability().debugLogExports(),
			"captureInputs", defaults.observability().captureInputs(),
			"captureOutputs", defaults.observability().captureOutputs(),
			"captureImages", defaults.observability().captureImages()
		));
		Files.writeString(yamlConfigPath, dumpYaml(yamlData), StandardCharsets.UTF_8);
	}

	private static void warnIfMalformedObject(Map<String, Object> root, String fieldName) {
		if (root == null || !root.containsKey(fieldName)) {
			return;
		}
		Object value = root.get(fieldName);
		if (value == null || value instanceof Map<?, ?>) {
			return;
		}
		Airicraft.LOGGER.warn("Expected {} to be a YAML mapping; ignoring malformed value and using defaults for nested fields", fieldName);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readObjectMap(Map<String, Object> root, String fieldName) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return Map.of();
		}
		Object value = root.get(fieldName);
		if (!(value instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Map<String, Object> typed = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			typed.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return typed;
	}

	private static Map<String, String> readStringMap(Map<String, Object> root, String fieldName, Map<String, String> fallback) {
		Map<String, Object> raw = readObjectMap(root, fieldName);
		if (raw.isEmpty()) {
			return fallback;
		}
		Map<String, String> typed = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			typed.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
		}
		return typed;
	}

	private static String readString(Map<String, Object> root, String fieldName, String fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		String value = String.valueOf(root.get(fieldName));
		return value == null ? fallback : value;
	}

	private static int readInt(Map<String, Object> root, String fieldName, int fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return Integer.parseInt(String.valueOf(value));
	}

	private static boolean readBoolean(Map<String, Object> root, String fieldName, boolean fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		return Boolean.parseBoolean(String.valueOf(value));
	}

	private static Yaml createYaml() {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		options.setIndent(2);
		return new Yaml(options);
	}

	private static String dumpYaml(Map<String, Object> yamlData) {
		return YAML.dump(yamlData);
	}
}
