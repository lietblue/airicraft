package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigLoaderTest {
	@Test
	void fromMapReadsVisionFieldsAndKeepsVisionDisabledWhenUnset() {
		AgentConfig defaults = AgentConfig.defaults();

		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"providerBaseUrl", "https://example.test/v1",
			"apiKey", "test-key",
			"model", "planner-model",
			"visionProviderBaseUrl", "https://vision.example.test/v1",
			"visionApiKey", "vision-key",
			"visionRequestTimeoutMillis", 7777,
			"visionImageDetail", "high",
			"plannerNativeVisionEnabled", true,
			"plannerUseJsonObjectResponseFormat", false
		), defaults);

		assertEquals("https://example.test/v1", parsed.llm().providerBaseUrl());
		assertEquals("test-key", parsed.llm().apiKey());
		assertEquals("planner-model", parsed.llm().model());
		assertEquals("https://vision.example.test/v1", parsed.llm().visionProviderBaseUrl());
		assertEquals("vision-key", parsed.llm().visionApiKey());
		assertEquals("", parsed.llm().visionModel());
		assertEquals(7777, parsed.llm().visionRequestTimeoutMillis());
		assertEquals(65_536, parsed.llm().plannerCompactionTriggerTokens());
		assertEquals(128, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(10, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(10, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(100, parsed.llm().plannerSessionCoalesceMaxMillis());
		assertEquals(30, parsed.idle().initialDelaySeconds());
		assertEquals(90, parsed.idle().cooldownSeconds());
		assertEquals("high", parsed.llm().visionImageDetail());
		assertEquals(true, parsed.llm().plannerNativeVisionEnabled());
		assertEquals(false, parsed.llm().plannerUseJsonObjectResponseFormat());
		assertFalse(parsed.llm().visionConfigured());
	}

	@Test
	void defaultsEnablePlannerJsonObjectResponseFormat() {
		assertEquals(true, AgentConfig.defaults().llm().plannerUseJsonObjectResponseFormat());
	}

	@Test
	void fromMapReadsPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerPendingSemanticEventCap", 256,
			"plannerSessionCoalesceStepMillis", 25,
			"plannerSessionCoalesceMinMillis", 30,
			"plannerSessionCoalesceMaxMillis", 90
		), AgentConfig.defaults());

		assertEquals(256, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(25, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(30, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(90, parsed.llm().plannerSessionCoalesceMaxMillis());
	}

	@Test
	void fromMapNormalizesPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerPendingSemanticEventCap", -10,
			"plannerSessionCoalesceStepMillis", -10,
			"plannerSessionCoalesceMinMillis", -5,
			"plannerSessionCoalesceMaxMillis", -1
		), AgentConfig.defaults());

		assertEquals(1, parsed.llm().plannerPendingSemanticEventCap());
		assertEquals(0, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(0, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(0, parsed.llm().plannerSessionCoalesceMaxMillis());

		AgentConfig reordered = AgentConfigLoader.fromMap(Map.of(
			"plannerSessionCoalesceStepMillis", 5,
			"plannerSessionCoalesceMinMillis", 50,
			"plannerSessionCoalesceMaxMillis", 20
		), AgentConfig.defaults());

		assertEquals(5, reordered.llm().plannerSessionCoalesceStepMillis());
		assertEquals(50, reordered.llm().plannerSessionCoalesceMinMillis());
		assertEquals(50, reordered.llm().plannerSessionCoalesceMaxMillis());
	}

	@Test
	void fromMapReadsIdleTimerFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"idleInitialDelaySeconds", 5,
			"idleCooldownSeconds", 0
		), AgentConfig.defaults());

		assertEquals(5, parsed.idle().initialDelaySeconds());
		assertEquals(0, parsed.idle().cooldownSeconds());
		assertFalse(parsed.idle().automaticEnabled());
	}

	@Test
	void fromMapNormalizesIdleTimerFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"idleInitialDelaySeconds", -5,
			"idleCooldownSeconds", -10
		), AgentConfig.defaults());

		assertEquals(0, parsed.idle().initialDelaySeconds());
		assertEquals(0, parsed.idle().cooldownSeconds());
		assertFalse(parsed.idle().automaticEnabled());
	}

	@Test
	void fromMapReadsObservabilityResourceAttributes() {
		AgentConfig defaults = AgentConfig.defaults();

		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"observability", Map.of(
				"enabled", true,
				"vendorProfile", "weave",
				"resourceAttributes", Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				)
			)
		), defaults);

		assertEquals(true, parsed.observability().enabled());
		assertEquals("weave", parsed.observability().vendorProfile());
		assertEquals("shinohara-rin", parsed.observability().resourceAttributes().get("wandb.entity"));
		assertEquals("airicraft", parsed.observability().resourceAttributes().get("wandb.project"));
	}

	@Test
	void fromMapStrictRejectsMalformedObservability() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			AgentConfigLoader.fromMapStrict(Map.of(
				"observability", "enabled"
			), AgentConfig.defaults())
		);

		assertEquals("observability must be a YAML mapping", exception.getMessage());
	}
}
