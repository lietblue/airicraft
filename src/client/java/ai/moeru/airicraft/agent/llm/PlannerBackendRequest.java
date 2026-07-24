package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerBackendRequest(
	long generation,
	int attempt,
	PlannerSessionPhase phase,
	PlannerRequest request,
	LlmConversation conversation
) {
	public PlannerBackendRequest {
		phase = Objects.requireNonNull(phase, "phase");
		request = Objects.requireNonNull(request, "request");
		conversation = Objects.requireNonNull(conversation, "conversation");
	}
}
