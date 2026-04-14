package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;

public record DialogueState(
	DialogueResponse lastResponse,
	boolean pendingReply,
	boolean degraded,
	int consecutiveFailureCount,
	LlmFailureType lastFailureType,
	long lastFailureTick
) {
	public static DialogueState initial() {
		return new DialogueState(null, false, false, 0, null, -1L);
	}

	DialogueState withLastResponse(DialogueResponse response) {
		return new DialogueState(response, pendingReply, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withPendingReply(boolean replacement) {
		return new DialogueState(lastResponse, replacement, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withDegraded(boolean replacement) {
		return new DialogueState(lastResponse, pendingReply, replacement, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withConsecutiveFailureCount(int replacement) {
		return new DialogueState(lastResponse, pendingReply, degraded, replacement, lastFailureType, lastFailureTick);
	}

	DialogueState withLastFailureType(LlmFailureType replacement) {
		return new DialogueState(lastResponse, pendingReply, degraded, consecutiveFailureCount, replacement, lastFailureTick);
	}

	DialogueState withLastFailureTick(long replacement) {
		return new DialogueState(lastResponse, pendingReply, degraded, consecutiveFailureCount, lastFailureType, replacement);
	}
}
