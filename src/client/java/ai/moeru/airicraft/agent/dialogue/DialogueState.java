package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;

public record DialogueState(
	DialogueResponse lastResponse,
	boolean pendingReply,
	String pendingReplyReason,
	boolean degraded,
	int consecutiveFailureCount,
	LlmFailureType lastFailureType,
	long lastFailureTick
) {
	public static DialogueState initial() {
		return new DialogueState(null, false, null, false, 0, null, -1L);
	}

	DialogueState withLastResponse(DialogueResponse response) {
		return new DialogueState(response, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withPendingReply(boolean replacement, String replacementReason) {
		return new DialogueState(lastResponse, replacement, replacement ? replacementReason : null, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withDegraded(boolean replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, replacement, consecutiveFailureCount, lastFailureType, lastFailureTick);
	}

	DialogueState withConsecutiveFailureCount(int replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, replacement, lastFailureType, lastFailureTick);
	}

	DialogueState withLastFailureType(LlmFailureType replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, replacement, lastFailureTick);
	}

	DialogueState withLastFailureTick(long replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, replacement);
	}
}
