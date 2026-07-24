package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigLoaderTest {
	@Test
	void defaultsToOpenAiCompatiblePlannerBackend() {
		AgentConfig.LlmConfig llm = AgentConfig.defaults().llm();

		assertEquals(AgentConfig.PlannerBackend.OPENAI_COMPATIBLE, llm.plannerBackend());
		assertEquals("codex", llm.codexAppServer().executable());
		assertFalse(llm.backendManagedHistory());
	}

	@Test
	void readsCodexAppServerPlannerBackendWithoutApiKey() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"plannerBackend", "codex-app-server",
			"codexAppServer", Map.of(
				"executable", "/opt/codex/bin/codex",
				"model", "local-codex-model",
				"startupTimeoutMillis", 4321,
				"turnTimeoutMillis", 98765
			)
		), AgentConfig.defaults());

		assertEquals(AgentConfig.PlannerBackend.CODEX_APP_SERVER, parsed.llm().plannerBackend());
		assertEquals("/opt/codex/bin/codex", parsed.llm().codexAppServer().executable());
		assertEquals("local-codex-model", parsed.llm().codexAppServer().model());
		assertEquals(4321, parsed.llm().codexAppServer().startupTimeoutMillis());
		assertEquals(98765, parsed.llm().codexAppServer().turnTimeoutMillis());
		assertTrue(parsed.llm().isConfigured());
		assertTrue(parsed.llm().backendManagedHistory());
	}

	@Test
	void strictConfigRejectsUnknownPlannerBackend() {
		assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.fromMapStrict(
			Map.of("plannerBackend", "polling-codex"),
			AgentConfig.defaults()
		));
	}

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
	void fromMapReadsAndNormalizesReflexFields() {
		AgentConfig parsed = AgentConfigLoader.fromMap(Map.of(
			"reflex", Map.of(
				"enabled", false,
				"lowAirTicks", -5,
				"defendMinHealthRatio", 1.5,
				"threatCooldownTicks", -2
			)
		), AgentConfig.defaults());

		assertFalse(parsed.reflex().enabled());
		assertEquals(0, parsed.reflex().lowAirTicks());
		assertEquals(1.0D, parsed.reflex().defendMinHealthRatio());
		assertEquals(0, parsed.reflex().threatCooldownTicks());
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

	@Test
	void motorShadowDefaultsDisabledAndFailClosed() {
		AgentConfig.Optimus3ShadowConfig shadow = AgentConfig.defaults().motor().optimus3Shadow();

		assertFalse(shadow.enabled());
		assertFalse(shadow.configured());
		assertEquals("", shadow.baseUrl());
		assertEquals("", shadow.apiKey());
		assertEquals("", shadow.modalKey());
		assertEquals("", shadow.modalSecret());
		assertEquals(180_000, shadow.sessionTimeoutMillis());
		assertEquals(250, shadow.requestTimeoutMillis());
		assertEquals(5_000, shadow.closeTimeoutMillis());
		assertEquals(7, shadow.policySeed());
	}

	@Test
	void fromMapReadsNestedMotorShadowConfig() {
		AgentConfig parsed = AgentConfigLoader.fromMapStrict(Map.of(
			"motor", Map.of(
				"optimus3Shadow", Map.of(
					"enabled", true,
					"baseUrl", "https://workspace--airicraft-optimus3-shadow.modal.run/",
					"apiKey", "secret-token",
					"modalKey", "wk-test",
					"modalSecret", "ws-test",
					"sessionTimeoutMillis", 200000,
					"requestTimeoutMillis", 400,
					"closeTimeoutMillis", 3000,
					"policySeed", 19
				)
			)
		), AgentConfig.defaults());

		AgentConfig.Optimus3ShadowConfig shadow = parsed.motor().optimus3Shadow();
		assertTrue(shadow.enabled());
		assertTrue(shadow.configured());
		assertEquals("https://workspace--airicraft-optimus3-shadow.modal.run", shadow.baseUrl());
		assertEquals("secret-token", shadow.apiKey());
		assertEquals("wk-test", shadow.modalKey());
		assertEquals("ws-test", shadow.modalSecret());
		assertEquals(200_000, shadow.sessionTimeoutMillis());
		assertEquals(400, shadow.requestTimeoutMillis());
		assertEquals(3_000, shadow.closeTimeoutMillis());
		assertEquals(19, shadow.policySeed());
	}

	@Test
	void fromMapStrictRejectsMalformedMotorConfig() {
		IllegalArgumentException motorException = assertThrows(IllegalArgumentException.class, () ->
			AgentConfigLoader.fromMapStrict(Map.of("motor", "enabled"), AgentConfig.defaults())
		);
		assertEquals("motor must be a YAML mapping", motorException.getMessage());

		IllegalArgumentException shadowException = assertThrows(IllegalArgumentException.class, () ->
			AgentConfigLoader.fromMapStrict(Map.of("motor", Map.of("optimus3Shadow", true)), AgentConfig.defaults())
		);
		assertEquals("optimus3Shadow must be a YAML mapping", shadowException.getMessage());
	}

	@Test
	void motorShadowRequiresHttpsExceptForLoopback() {
		assertTrue(new AgentConfig.Optimus3ShadowConfig(true, "http://127.0.0.1:8000", "", 250, 7).configured());
		assertTrue(new AgentConfig.Optimus3ShadowConfig(true, "http://[::1]:8000", "", 250, 7).configured());

		IllegalArgumentException insecure = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(true, "http://motor.example.test", "", 250, 7)
		);
		assertEquals("motor.optimus3Shadow.baseUrl requires HTTPS unless it targets loopback", insecure.getMessage());

		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(true, "", "", 250, 7)
		);
		assertEquals("motor.optimus3Shadow.baseUrl is required when enabled", missing.getMessage());

		IllegalArgumentException partialModalAuth = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(false, "", "", "wk-test", "", 180_000, 250, 7)
		);

		IllegalArgumentException unboundModalAuth = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(
				true,
				"https://motor.example.test",
				"",
				"wk-test",
				"ws-test",
				180_000,
				250,
				7
			)
		);
		assertEquals(
			"motor.optimus3Shadow Modal proxy credentials require an https://*.modal.run or loopback endpoint",
			unboundModalAuth.getMessage()
		);
		assertEquals(
			"motor.optimus3Shadow.modalKey and modalSecret must be configured together",
			partialModalAuth.getMessage()
		);

		AgentConfig maxSeed = AgentConfigLoader.fromMapStrict(Map.of(
			"motor", Map.of("optimus3Shadow", Map.of("policySeed", 4_294_967_295L))
		), AgentConfig.defaults());
		assertEquals(4_294_967_295L, maxSeed.motor().optimus3Shadow().policySeed());
		IllegalArgumentException invalidSeed = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(false, "", "", "", "", 180_000, 250, -1L)
		);
		assertEquals(
			"motor.optimus3Shadow.policySeed must be between 0 and 4294967295",
			invalidSeed.getMessage()
		);
		IllegalArgumentException invalidTimeout = assertThrows(IllegalArgumentException.class, () ->
			new AgentConfig.Optimus3ShadowConfig(false, "", "", "", "", 180_000, 0, 5_000, 7L)
		);
		assertEquals("motor.optimus3Shadow timeouts must be positive", invalidTimeout.getMessage());
	}
}
