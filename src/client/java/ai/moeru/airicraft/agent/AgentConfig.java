package ai.moeru.airicraft.agent;

public record AgentConfig(
	boolean verificationEnabled,
	boolean verificationAutoRunAll,
	LlmConfig llm
) {
	public static AgentConfig defaults() {
		return new AgentConfig(false, false, LlmConfig.defaults());
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
		boolean plannerNativeVisionEnabled
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
				128,
				3,
				10,
				10,
				100,
				visionImageDetail,
				plannerNativeVisionEnabled
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
				false
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
}
