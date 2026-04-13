package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbodiedAgentRuntimeTest {
	@Test
	void suppressesRecentEchoOfAgentOwnPublicChat() {
		assertTrue(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void doesNotSuppressDifferentOrStaleChat() {
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"follow me",
			"Player918",
			"hello there",
			120L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			200L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"magpie",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void detectsLocalControllerMessagesByMatchingClientPlayerName() {
		assertTrue(EmbodiedAgentRuntime.isLocalControllerMessage("Player918", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage("magpie", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage(null, "Player918"));
	}

	@Test
	void fallsBackToLastKnownPlayerHealthWhenObservedHealthAlreadyDropped() {
		float effective = EmbodiedAgentRuntime.effectiveHealthBefore(20.0F, 19.0F, 19.0F);

		assertEquals(20.0F, effective);
	}

	@Test
	void keepsObservedHealthBeforeWhenNoHigherBaselineExists() {
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(null, 19.0F, 19.0F));
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(18.0F, 19.0F, 19.0F));
	}
}
