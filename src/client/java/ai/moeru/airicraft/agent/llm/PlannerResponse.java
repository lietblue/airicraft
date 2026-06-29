package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import com.google.gson.JsonElement;

import java.util.List;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges,
	PlannerToolCall toolCall,
	List<PlannerToolCall> toolCalls,
	List<PlannerChatMessage> chatMessages,
	JsonElement rawAssistantContent
) {
	public PlannerResponse(String replyText, PlannerIntent intent) {
		this(replyText, intent, null, null, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest) {
		this(replyText, intent, toolRequest, null, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest, EventPolicyChanges eventPolicyChanges) {
		this(replyText, intent, toolRequest, eventPolicyChanges, null, null);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		JsonElement rawAssistantContent
	) {
		this(replyText, intent, toolRequest, eventPolicyChanges, null, rawAssistantContent);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		PlannerToolCall toolCall,
		JsonElement rawAssistantContent
	) {
		this(
			replyText,
			intent,
			toolRequest,
			eventPolicyChanges,
			toolCall,
			toolCall == null ? List.of() : List.of(toolCall),
			null,
			rawAssistantContent
		);
	}

	public PlannerResponse(String replyText, PlannerToolCall toolCall, JsonElement rawAssistantContent) {
		this(replyText, new PlannerIntent(toolCall == null ? "reply_only" : "none", null, null), null, null, toolCall, rawAssistantContent);
	}

	public PlannerResponse(String replyText, List<PlannerToolCall> toolCalls, JsonElement rawAssistantContent) {
		this(
			replyText,
			new PlannerIntent(toolCalls == null || toolCalls.isEmpty() ? "reply_only" : "none", null, null),
			null,
			null,
			null,
			toolCalls,
			null,
			rawAssistantContent
		);
	}

	public PlannerResponse(List<PlannerChatMessage> chatMessages, PlannerIntent intent, JsonElement rawAssistantContent) {
		this("", intent, null, null, null, List.of(), chatMessages, rawAssistantContent);
	}

	public static PlannerResponse toolCalls(List<PlannerToolCall> toolCalls, JsonElement rawAssistantContent) {
		List<PlannerToolCall> normalizedToolCalls = normalizeToolCalls(toolCalls, null);
		return new PlannerResponse(
			"",
			new PlannerIntent(normalizedToolCalls.isEmpty() ? "reply_only" : "none", null, null),
			null,
			null,
			normalizedToolCalls.isEmpty() ? null : normalizedToolCalls.getFirst(),
			normalizedToolCalls,
			null,
			rawAssistantContent
		);
	}

	public PlannerResponse {
		replyText = replyText == null ? "" : replyText;
		toolCalls = normalizeToolCalls(toolCalls, toolCall);
		toolCall = toolCalls.isEmpty() ? null : toolCalls.getFirst();
		chatMessages = normalizeChatMessages(chatMessages, replyText);
		if (replyText.isBlank() && !chatMessages.isEmpty()) {
			replyText = String.join(" ", chatMessages.stream()
				.map(PlannerChatMessage::text)
				.filter(text -> text != null && !text.isBlank())
				.toList()).strip();
		}
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}

	private static List<PlannerToolCall> normalizeToolCalls(List<PlannerToolCall> toolCalls, PlannerToolCall toolCall) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return toolCall == null ? List.of() : List.of(toolCall);
		}
		return toolCalls.stream()
			.filter(call -> call != null)
			.toList();
	}

	private static List<PlannerChatMessage> normalizeChatMessages(List<PlannerChatMessage> chatMessages, String replyText) {
		if (chatMessages != null && !chatMessages.isEmpty()) {
			return chatMessages.stream()
				.filter(message -> message != null && message.text() != null && !message.text().isBlank())
				.toList();
		}
		if (replyText == null || replyText.isBlank()) {
			return List.of();
		}
		return List.of(PlannerChatMessage.immediate(replyText));
	}

	public PlannerResponse withChatMessages(List<PlannerChatMessage> replacementChatMessages) {
		return new PlannerResponse(
			"",
			intent,
			toolRequest,
			eventPolicyChanges,
			toolCall,
			toolCalls,
			replacementChatMessages,
			rawAssistantContent
		);
	}

	public PlannerResponse withToolCalls(List<PlannerToolCall> replacementToolCalls) {
		return new PlannerResponse(
			replyText,
			intent,
			toolRequest,
			eventPolicyChanges,
			replacementToolCalls == null || replacementToolCalls.isEmpty() ? null : replacementToolCalls.getFirst(),
			replacementToolCalls,
			chatMessages,
			rawAssistantContent
		);
	}
}
