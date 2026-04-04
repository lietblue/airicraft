package ai.moeru.airicraft.agent.events;

public record EventPolicyIntervention(
	long sourceEventSeqNo,
	String sourceEventType,
	EventPolicyEffect effect,
	String matchedRuleId,
	String reason,
	long timestampMs
) {
}
