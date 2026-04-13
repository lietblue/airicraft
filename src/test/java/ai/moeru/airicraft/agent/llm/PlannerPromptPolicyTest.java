package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptPolicyTest {
	@Test
	void systemPromptExplainsInventoryDeltaEvidenceUsesMissionGainNotAbsoluteInventory() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("INVENTORY_DELTA_AT_LEAST"));
		assertTrue(prompt.contains("mission start") || prompt.contains("mission began"));
		assertTrue(prompt.contains("not absolute inventory") || prompt.contains("not the current total inventory"));
	}
}
