package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;

public record DialogueResponse(
	String text,
	DialogueIntent intent,
	long tick,
	EventPolicyChanges eventPolicyChanges
) {
	public DialogueResponse(String text, DialogueIntent intent, long tick) {
		this(text, intent, tick, null);
	}
}
