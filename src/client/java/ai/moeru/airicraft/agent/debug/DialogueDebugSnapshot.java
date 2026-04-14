package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.llm.LlmFailureType;

public record DialogueDebugSnapshot(
	boolean pendingReply,
	String pendingReplyReason,
	DialogueResponse lastResponse,
	boolean degraded,
	int consecutiveFailureCount,
	LlmFailureType lastFailureType,
	long lastFailureTick
) {
	public static DialogueDebugSnapshot empty() {
		return new DialogueDebugSnapshot(false, null, null, false, 0, null, -1L);
	}
}
