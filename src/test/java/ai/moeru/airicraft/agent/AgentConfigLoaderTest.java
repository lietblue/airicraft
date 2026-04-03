package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
			"plannerNativeVisionEnabled", true
		), defaults);

		assertEquals("https://example.test/v1", parsed.llm().providerBaseUrl());
		assertEquals("test-key", parsed.llm().apiKey());
		assertEquals("planner-model", parsed.llm().model());
		assertEquals("https://vision.example.test/v1", parsed.llm().visionProviderBaseUrl());
		assertEquals("vision-key", parsed.llm().visionApiKey());
		assertEquals("", parsed.llm().visionModel());
		assertEquals(7777, parsed.llm().visionRequestTimeoutMillis());
		assertEquals(65_536, parsed.llm().plannerCompactionTriggerTokens());
		assertEquals(10, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(10, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(100, parsed.llm().plannerSessionCoalesceMaxMillis());
		assertEquals("high", parsed.llm().visionImageDetail());
		assertEquals(true, parsed.llm().plannerNativeVisionEnabled());
		assertFalse(parsed.llm().visionConfigured());
	}

	@Test
	void fromMapReadsPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerSessionCoalesceStepMillis", 25,
			"plannerSessionCoalesceMinMillis", 30,
			"plannerSessionCoalesceMaxMillis", 90
		), AgentConfig.defaults());

		assertEquals(25, parsed.llm().plannerSessionCoalesceStepMillis());
		assertEquals(30, parsed.llm().plannerSessionCoalesceMinMillis());
		assertEquals(90, parsed.llm().plannerSessionCoalesceMaxMillis());
	}

	@Test
	void fromMapNormalizesPlannerCoalesceFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerSessionCoalesceStepMillis", -10,
			"plannerSessionCoalesceMinMillis", -5,
			"plannerSessionCoalesceMaxMillis", -1
		), AgentConfig.defaults());

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
}
