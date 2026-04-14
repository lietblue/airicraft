package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;

import java.util.List;

public record DialogueSnapshot(
	List<DialogueTurn> recentTurns,
	DialogueResponse lastResponse,
	boolean pendingReply,
	String pendingReplyReason,
	boolean degraded,
	int consecutiveFailureCount,
	LlmFailureType lastFailureType,
	long lastFailureTick
) {
}
