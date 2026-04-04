package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges
) {
	public PlannerResponse(String replyText, PlannerIntent intent) {
		this(replyText, intent, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest) {
		this(replyText, intent, toolRequest, null);
	}
}
