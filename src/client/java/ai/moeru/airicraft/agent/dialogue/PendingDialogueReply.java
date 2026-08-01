package ai.moeru.airicraft.agent.dialogue;

import java.util.Objects;

public record PendingDialogueReply(
	long id,
	DialogueResponse response,
	String reason,
	long readyTick
) {
	public PendingDialogueReply {
		response = Objects.requireNonNull(response, "response");
		reason = reason == null ? "" : reason;
	}
}
