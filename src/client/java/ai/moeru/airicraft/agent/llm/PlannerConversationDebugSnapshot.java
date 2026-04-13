package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PlannerConversationDebugSnapshot(
	long generation,
	String phase,
	int attempt,
	List<PlannerConversationDebugMessage> messages
) {
	public PlannerConversationDebugSnapshot {
		phase = phase == null ? "UNKNOWN" : phase;
		attempt = Math.max(0, attempt);
		messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
	}

	public static PlannerConversationDebugSnapshot empty() {
		return new PlannerConversationDebugSnapshot(0L, "UNKNOWN", 0, List.of());
	}

	public static PlannerConversationDebugSnapshot fromConversation(
		long generation,
		PlannerSessionPhase phase,
		int attempt,
		LlmConversation conversation
	) {
		if (conversation == null) {
			return empty();
		}
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		for (LlmChatMessage message : conversation.messages()) {
			messages.add(new PlannerConversationDebugMessage(
				message.role(),
				PlannerConversationDebugKind.fromMessageKind(message.kind()),
				message.content(),
				generation,
				phase == null ? "UNKNOWN" : phase.name(),
				attempt,
				message.hasImageAttachment()
			));
		}
		return new PlannerConversationDebugSnapshot(generation, phase == null ? "UNKNOWN" : phase.name(), attempt, messages);
	}

	public boolean isEmpty() {
		return messages.isEmpty();
	}

	public PlannerConversationDebugSnapshot withAppended(PlannerConversationDebugMessage message) {
		ArrayList<PlannerConversationDebugMessage> updated = new ArrayList<>(messages);
		updated.add(Objects.requireNonNull(message, "message"));
		return new PlannerConversationDebugSnapshot(generation, phase, attempt, updated);
	}
}
