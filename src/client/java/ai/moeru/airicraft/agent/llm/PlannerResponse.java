package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import com.google.gson.JsonElement;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges,
	JsonElement rawAssistantContent
) {
	public PlannerResponse(String replyText, PlannerIntent intent) {
		this(replyText, intent, null, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest) {
		this(replyText, intent, toolRequest, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest, EventPolicyChanges eventPolicyChanges) {
		this(replyText, intent, toolRequest, eventPolicyChanges, null);
	}

	public PlannerResponse {
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}
}
