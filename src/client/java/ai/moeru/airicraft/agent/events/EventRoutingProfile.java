package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.llm.PlannerTriggerType;

public record EventRoutingProfile(
	String eventType,
	boolean semanticEligible,
	PlannerTriggerType triggerType,
	boolean policyBypass
) {
	public static EventRoutingProfile rawOnly(String eventType) {
		return new EventRoutingProfile(eventType, false, null, false);
	}

	public boolean triggerEligible() {
		return triggerType != null;
	}
}
