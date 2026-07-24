package ai.moeru.airicraft.agent;

public record AgentConfig(
	boolean verificationEnabled,
	boolean verificationAutoRunAll,
	LlmConfig llm,
	IdleConfig idle,
	ReflexConfig reflex,
	ObservabilityConfig observability,
	MotorConfig motor
) {
	public AgentConfig {
		llm = llm == null ? LlmConfig.defaults() : llm;
		idle = idle == null ? IdleConfig.defaults() : idle;
		reflex = reflex == null ? ReflexConfig.defaults() : reflex;
		observability = observability == null ? ObservabilityConfig.defaults() : observability;
		motor = motor == null ? MotorConfig.defaults() : motor;
	}

	public AgentConfig(
		boolean verificationEnabled,
		boolean verificationAutoRunAll,
		LlmConfig llm,
		IdleConfig idle,
		ObservabilityConfig observability
	) {
		this(
			verificationEnabled,
			verificationAutoRunAll,
			llm,
			idle,
			ReflexConfig.defaults(),
			observability,
			MotorConfig.defaults()
		);
	}

	public static AgentConfig defaults() {
		return new AgentConfig(
			false,
			false,
			LlmConfig.defaults(),
			IdleConfig.defaults(),
			ReflexConfig.defaults(),
			ObservabilityConfig.defaults(),
			MotorConfig.defaults()
		);
	}

	public record LlmConfig(
		String providerBaseUrl,
		String apiKey,
		String model,
		String visionProviderBaseUrl,
		String visionApiKey,
		String visionModel,
		int requestTimeoutMillis,
		int visionRequestTimeoutMillis,
		int maxRecentConversationTurns,
		int plannerCompactionTriggerTokens,
		int plannerPendingSemanticEventCap,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		String visionImageDetail,
		boolean plannerNativeVisionEnabled,
		boolean plannerUseJsonObjectResponseFormat,
		PlannerBackend plannerBackend,
		CodexAppServerConfig codexAppServer
	) {
		public LlmConfig {
			plannerPendingSemanticEventCap = Math.max(1, plannerPendingSemanticEventCap);
			plannerSessionCoalesceStepMillis = Math.max(0, plannerSessionCoalesceStepMillis);
			plannerSessionCoalesceMinMillis = Math.max(0, plannerSessionCoalesceMinMillis);
			plannerSessionCoalesceMaxMillis = Math.max(plannerSessionCoalesceMinMillis, plannerSessionCoalesceMaxMillis);
			plannerBackend = plannerBackend == null ? PlannerBackend.OPENAI_COMPATIBLE : plannerBackend;
			codexAppServer = codexAppServer == null ? CodexAppServerConfig.defaults() : codexAppServer;
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			int plannerPendingSemanticEventCap,
			int plannerSessionMaxConcurrentAttempts,
			int plannerSessionCoalesceStepMillis,
			int plannerSessionCoalesceMinMillis,
			int plannerSessionCoalesceMaxMillis,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				plannerPendingSemanticEventCap,
				plannerSessionMaxConcurrentAttempts,
				plannerSessionCoalesceStepMillis,
				plannerSessionCoalesceMinMillis,
				plannerSessionCoalesceMaxMillis,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat,
				PlannerBackend.OPENAI_COMPATIBLE,
				CodexAppServerConfig.defaults()
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				visionImageDetail,
				plannerNativeVisionEnabled,
				true
			);
		}

		public LlmConfig(
			String providerBaseUrl,
			String apiKey,
			String model,
			String visionProviderBaseUrl,
			String visionApiKey,
			String visionModel,
			int requestTimeoutMillis,
			int visionRequestTimeoutMillis,
			int maxRecentConversationTurns,
			int plannerCompactionTriggerTokens,
			String visionImageDetail,
			boolean plannerNativeVisionEnabled,
			boolean plannerUseJsonObjectResponseFormat
		) {
			this(
				providerBaseUrl,
				apiKey,
				model,
				visionProviderBaseUrl,
				visionApiKey,
				visionModel,
				requestTimeoutMillis,
				visionRequestTimeoutMillis,
				maxRecentConversationTurns,
				plannerCompactionTriggerTokens,
				128,
				3,
				10,
				10,
				100,
				visionImageDetail,
				plannerNativeVisionEnabled,
				plannerUseJsonObjectResponseFormat
			);
		}

		public static LlmConfig defaults() {
			return new LlmConfig(
				"https://api.openai.com/v1",
				"",
				"",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				128,
				3,
				10,
					10,
					100,
					"low",
					false,
					true
				);
		}

		public boolean isConfigured() {
			if (plannerBackend == PlannerBackend.CODEX_APP_SERVER) {
				return codexAppServer.isConfigured();
			}
			return providerBaseUrl != null
				&& !providerBaseUrl.isBlank()
				&& apiKey != null
				&& !apiKey.isBlank()
				&& model != null
				&& !model.isBlank();
		}

		public boolean backendManagedHistory() {
			return plannerBackend == PlannerBackend.CODEX_APP_SERVER;
		}

		public boolean visionConfigured() {
			return visionProviderBaseUrl != null
				&& !visionProviderBaseUrl.isBlank()
				&& visionApiKey != null
				&& !visionApiKey.isBlank()
				&& visionModel != null
				&& !visionModel.isBlank();
		}

		public ai.moeru.airicraft.agent.llm.PlannerVisionMode plannerVisionMode() {
			return plannerNativeVisionEnabled
				? ai.moeru.airicraft.agent.llm.PlannerVisionMode.NATIVE_TOOL_IMAGE
				: ai.moeru.airicraft.agent.llm.PlannerVisionMode.EXTERNAL_SUMMARY;
		}
	}

	public enum PlannerBackend {
		OPENAI_COMPATIBLE("openai-compatible"),
		CODEX_APP_SERVER("codex-app-server");

		private final String wireValue;

		PlannerBackend(String wireValue) {
			this.wireValue = wireValue;
		}

		public String wireValue() {
			return wireValue;
		}

		public static PlannerBackend fromWireValue(String value) {
			for (PlannerBackend backend : values()) {
				if (backend.wireValue.equalsIgnoreCase(value == null ? "" : value.trim())) {
					return backend;
				}
			}
			throw new IllegalArgumentException("Unsupported plannerBackend: " + value);
		}
	}

	public record CodexAppServerConfig(
		String executable,
		String model,
		int startupTimeoutMillis,
		int turnTimeoutMillis
	) {
		public CodexAppServerConfig {
			executable = executable == null ? "" : executable.trim();
			model = model == null ? "" : model.trim();
			startupTimeoutMillis = Math.max(1, startupTimeoutMillis);
			turnTimeoutMillis = Math.max(1, turnTimeoutMillis);
		}

		public static CodexAppServerConfig defaults() {
			return new CodexAppServerConfig("codex", "", 10_000, 120_000);
		}

		public boolean isConfigured() {
			return !executable.isBlank();
		}
	}

	public record IdleConfig(
		int initialDelaySeconds,
		int cooldownSeconds
	) {
		public IdleConfig {
			initialDelaySeconds = Math.max(0, initialDelaySeconds);
			cooldownSeconds = Math.max(0, cooldownSeconds);
		}

		public static IdleConfig defaults() {
			return new IdleConfig(30, 90);
		}

		public boolean automaticEnabled() {
			return initialDelaySeconds > 0 && cooldownSeconds > 0;
		}
	}

	public record ReflexConfig(
		boolean enabled,
		int lowAirTicks,
		double defendMinHealthRatio,
		int threatCooldownTicks
	) {
		public ReflexConfig {
			lowAirTicks = Math.max(0, lowAirTicks);
			defendMinHealthRatio = Math.max(0.0D, Math.min(1.0D, defendMinHealthRatio));
			threatCooldownTicks = Math.max(0, threatCooldownTicks);
		}

		public static ReflexConfig defaults() {
			return new ReflexConfig(true, 100, 0.5D, 60);
		}
	}

	public record ObservabilityConfig(
		boolean enabled,
		String exporter,
		String otlpEndpoint,
		java.util.Map<String, String> otlpHeaders,
		java.util.Map<String, String> resourceAttributes,
		String vendorProfile,
		boolean debugLogExports,
		boolean captureInputs,
		boolean captureOutputs,
		boolean captureImages
	) {
		public ObservabilityConfig {
			exporter = exporter == null ? "otlp_http" : exporter;
			otlpEndpoint = otlpEndpoint == null ? "" : otlpEndpoint;
			otlpHeaders = otlpHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(otlpHeaders);
			resourceAttributes = resourceAttributes == null ? java.util.Map.of() : java.util.Map.copyOf(resourceAttributes);
			vendorProfile = vendorProfile == null ? "generic" : vendorProfile;
		}

		public static ObservabilityConfig defaults() {
			return new ObservabilityConfig(
				false,
				"otlp_http",
				"",
				java.util.Map.of(),
				java.util.Map.of(),
				"generic",
				false,
				false,
				false,
				false
			);
		}
	}

	public record MotorConfig(Optimus3ShadowConfig optimus3Shadow) {
		public MotorConfig {
			optimus3Shadow = optimus3Shadow == null ? Optimus3ShadowConfig.defaults() : optimus3Shadow;
		}

		public static MotorConfig defaults() {
			return new MotorConfig(Optimus3ShadowConfig.defaults());
		}
	}

	public record Optimus3ShadowConfig(
		boolean enabled,
		String baseUrl,
		String apiKey,
		String modalKey,
		String modalSecret,
		int sessionTimeoutMillis,
		int requestTimeoutMillis,
		int closeTimeoutMillis,
		long policySeed
	) {
		public Optimus3ShadowConfig {
			baseUrl = normalizeBaseUrl(baseUrl);
			apiKey = apiKey == null ? "" : apiKey.trim();
			modalKey = modalKey == null ? "" : modalKey.trim();
			modalSecret = modalSecret == null ? "" : modalSecret.trim();
			if (sessionTimeoutMillis <= 0 || requestTimeoutMillis <= 0 || closeTimeoutMillis <= 0) {
				throw new IllegalArgumentException("motor.optimus3Shadow timeouts must be positive");
			}
			if (policySeed < 0L || policySeed > 0xffff_ffffL) {
				throw new IllegalArgumentException("motor.optimus3Shadow.policySeed must be between 0 and 4294967295");
			}
			if (modalKey.isBlank() != modalSecret.isBlank()) {
				throw new IllegalArgumentException("motor.optimus3Shadow.modalKey and modalSecret must be configured together");
			}
			if (!modalKey.isBlank()) {
				validateModalProxyEndpoint(baseUrl);
			}
			if (enabled) {
				validateEndpoint(baseUrl);
			}
		}

		public Optimus3ShadowConfig(
			boolean enabled,
			String baseUrl,
			String apiKey,
			int requestTimeoutMillis,
			long policySeed
		) {
			this(enabled, baseUrl, apiKey, "", "", 180_000, requestTimeoutMillis, 5_000, policySeed);
		}

		public Optimus3ShadowConfig(
			boolean enabled,
			String baseUrl,
			String apiKey,
			String modalKey,
			String modalSecret,
			int sessionTimeoutMillis,
			int requestTimeoutMillis,
			long policySeed
		) {
			this(enabled, baseUrl, apiKey, modalKey, modalSecret, sessionTimeoutMillis, requestTimeoutMillis, 5_000, policySeed);
		}

		public static Optimus3ShadowConfig defaults() {
			return new Optimus3ShadowConfig(false, "", "", "", "", 180_000, 250, 5_000, 7);
		}

		public boolean configured() {
			return enabled && !baseUrl.isBlank();
		}

		private static String normalizeBaseUrl(String value) {
			String normalized = value == null ? "" : value.trim();
			while (normalized.endsWith("/")) {
				normalized = normalized.substring(0, normalized.length() - 1);
			}
			return normalized;
		}

		private static void validateEndpoint(String value) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("motor.optimus3Shadow.baseUrl is required when enabled");
			}
			java.net.URI uri;
			try {
				uri = java.net.URI.create(value);
			}
			catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("motor.optimus3Shadow.baseUrl must be a valid URL", exception);
			}
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
				throw new IllegalArgumentException("motor.optimus3Shadow.baseUrl must be an HTTP(S) origin or base path");
			}
			if ("https".equalsIgnoreCase(scheme)) {
				return;
			}
			if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(host)) {
				return;
			}
			throw new IllegalArgumentException("motor.optimus3Shadow.baseUrl requires HTTPS unless it targets loopback");
		}

		private static void validateModalProxyEndpoint(String value) {
			validateEndpoint(value);
			java.net.URI uri = java.net.URI.create(value);
			String host = uri.getHost();
			if (isLoopbackHost(host)) {
				return;
			}
			if ("https".equalsIgnoreCase(uri.getScheme()) && host.toLowerCase(java.util.Locale.ROOT).endsWith(".modal.run")) {
				return;
			}
			throw new IllegalArgumentException(
				"motor.optimus3Shadow Modal proxy credentials require an https://*.modal.run or loopback endpoint"
			);
		}

		private static boolean isLoopbackHost(String host) {
			if (host == null) {
				return false;
			}
			String normalized = host.toLowerCase(java.util.Locale.ROOT);
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
					int parsed = Integer.parseInt(octet);
					if (parsed < 0 || parsed > 255) {
						return false;
					}
				}
				return true;
			}
			catch (NumberFormatException exception) {
				return false;
			}
		}
	}
}
