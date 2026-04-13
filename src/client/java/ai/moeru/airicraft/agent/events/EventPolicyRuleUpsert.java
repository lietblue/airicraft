package ai.moeru.airicraft.agent.events;

public record EventPolicyRuleUpsert(
	String ruleId,
	String effect,
	EventPolicyMatch match,
	String reason
) {
}
