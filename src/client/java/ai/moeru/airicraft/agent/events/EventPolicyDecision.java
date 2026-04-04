package ai.moeru.airicraft.agent.events;

public record EventPolicyDecision(
	EventPolicyEffect effect,
	String matchedRuleId,
	String reason,
	boolean bypassed
) {
	public static EventPolicyDecision allow() {
		return new EventPolicyDecision(EventPolicyEffect.ALLOW, null, null, false);
	}

	public static EventPolicyDecision bypass() {
		return new EventPolicyDecision(EventPolicyEffect.ALLOW, null, null, true);
	}

	public boolean intervened() {
		return !bypassed && effect != EventPolicyEffect.ALLOW;
	}
}
