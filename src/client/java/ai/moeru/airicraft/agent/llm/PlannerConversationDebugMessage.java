package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerConversationDebugMessage(
	String role,
	PlannerConversationDebugKind kind,
	String text,
	long generation,
	String phase,
	int attempt,
	boolean hasImageAttachment
) {
	public PlannerConversationDebugMessage {
		role = role == null || role.isBlank() ? "system" : role;
		kind = Objects.requireNonNull(kind, "kind");
		text = text == null ? "" : text;
		phase = phase == null ? "UNKNOWN" : phase;
		attempt = Math.max(0, attempt);
	}
}
