package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;

public record DialogueResponse(
	String text,
	DialogueIntent intent,
	long tick,
	int delayTicks,
	EventPolicyChanges eventPolicyChanges
) {
	public DialogueResponse(String text, DialogueIntent intent, long tick) {
		this(text, intent, tick, 0, null);
	}

	public DialogueResponse(String text, DialogueIntent intent, long tick, EventPolicyChanges eventPolicyChanges) {
		this(text, intent, tick, 0, eventPolicyChanges);
	}

	public DialogueResponse {
		delayTicks = Math.max(0, delayTicks);
	}
}
