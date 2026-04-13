package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlannerTriggerBatchTest {
	@Test
	void pickupOnlyBatchUsesGenericWakePrompt() {
		PlannerTriggerBatch batch = PlannerTriggerBatch.of(List.of(
			PlannerTrigger.pending(PlannerTriggerType.PICKUP, "self", "Picked up 2x minecraft:oak_log.", 10L, 20L)
		));

		assertEquals("Recent context updates require one combined response.", batch.primaryMessage());
		assertEquals("Recent context updates require one combined response.", batch.renderPrompt());
	}

	@Test
	void pickupIsOmittedFromCombinedPromptWhenChatIsPresent() {
		PlannerTriggerBatch batch = PlannerTriggerBatch.of(List.of(
			PlannerTrigger.pending(PlannerTriggerType.CHAT, "Alice", "hello", 10L, 20L),
			PlannerTrigger.pending(PlannerTriggerType.PICKUP, "self", "Picked up 2x minecraft:oak_log.", 10L, 21L)
		));

		assertEquals("""
			Recent updates requiring one combined response:
			- [chat][Alice] hello
			
			Respond once to the combined latest context above.""", batch.renderPrompt());
	}

	@Test
	void damageOnlyBatchUsesGenericWakePrompt() {
		PlannerTriggerBatch batch = PlannerTriggerBatch.of(List.of(
			PlannerTrigger.pending(PlannerTriggerType.DAMAGE, "self", "I took 2 damage from Zombie.", 10L, 20L)
		));

		assertEquals("Recent context updates require one combined response.", batch.primaryMessage());
		assertEquals("Recent context updates require one combined response.", batch.renderPrompt());
	}

	@Test
	void damageIsOmittedFromCombinedPromptWhenChatIsPresent() {
		PlannerTriggerBatch batch = PlannerTriggerBatch.of(List.of(
			PlannerTrigger.pending(PlannerTriggerType.CHAT, "Alice", "watch out", 10L, 20L),
			PlannerTrigger.pending(PlannerTriggerType.DAMAGE, "self", "I took 2 damage from Zombie.", 10L, 21L)
		));

		assertEquals("""
			Recent updates requiring one combined response:
			- [chat][Alice] watch out
			
			Respond once to the combined latest context above.""", batch.renderPrompt());
	}
}
