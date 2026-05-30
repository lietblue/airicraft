package ai.moeru.airicraft.agent;

public record AgentConfig(
	boolean verificationEnabled,
	boolean verificationAutoRunAll,
	LlmConfig llm,
	IdleConfig idle,
	ObservabilityConfig observability
) {
	public AgentConfig {
		llm = llm == null ? LlmConfig.defaults() : llm;
		idle = idle == null ? IdleConfig.defaults() : idle;
		observability = observability == null ? ObservabilityConfig.defaults() : observability;
	}

	public static AgentConfig defaults() {
		return new AgentConfig(false, false, LlmConfig.defaults(), IdleConfig.defaults(), ObservabilityConfig.defaults());
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
		boolean plannerUseJsonObjectResponseFormat
	) {
		public LlmConfig {
			plannerPendingSemanticEventCap = Math.max(1, plannerPendingSemanticEventCap);
			plannerSessionCoalesceStepMillis = Math.max(0, plannerSessionCoalesceStepMillis);
			plannerSessionCoalesceMinMillis = Math.max(0, plannerSessionCoalesceMinMillis);
			plannerSessionCoalesceMaxMillis = Math.max(plannerSessionCoalesceMinMillis, plannerSessionCoalesceMaxMillis);
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
			return providerBaseUrl != null
				&& !providerBaseUrl.isBlank()
				&& apiKey != null
				&& !apiKey.isBlank()
				&& model != null
				&& !model.isBlank();
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
}
