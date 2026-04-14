package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;

import java.util.List;
import java.util.Objects;

public record ConversationSourcesDebugSnapshot(
	PlannerConversationDebugSnapshot canonicalConversation,
	PlannerConversationDebugSnapshot projectedConversation,
	int canonicalMessageCount,
	int projectedMessageCount,
	int canonicalUserTurnCount,
	int projectedUserTurnCount,
	List<String> hiddenKinds
) {
	public ConversationSourcesDebugSnapshot {
		canonicalConversation = canonicalConversation == null ? PlannerConversationDebugSnapshot.empty() : canonicalConversation;
		projectedConversation = projectedConversation == null ? PlannerConversationDebugSnapshot.empty() : projectedConversation;
		hiddenKinds = List.copyOf(Objects.requireNonNullElse(hiddenKinds, List.of()));
	}

	public static ConversationSourcesDebugSnapshot empty() {
		return new ConversationSourcesDebugSnapshot(
			PlannerConversationDebugSnapshot.empty(),
			PlannerConversationDebugSnapshot.empty(),
			0,
			0,
			0,
			0,
			List.of()
		);
	}
}
