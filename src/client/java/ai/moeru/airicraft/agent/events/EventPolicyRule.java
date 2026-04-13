package ai.moeru.airicraft.agent.events;

import java.util.Objects;

public record EventPolicyRule(
	String ruleId,
	EventPolicyEffect effect,
	EventPolicyMatch match,
	String reason,
	long createdAtMs,
	Long lastMatchedAtMs,
	long matchCount,
	String source
) {
	public EventPolicyRule {
		ruleId = normalize(ruleId);
		effect = Objects.requireNonNull(effect, "effect");
		match = Objects.requireNonNull(match, "match");
		reason = normalize(reason);
		source = normalize(source);
	}

	public EventPolicyRule noteMatched(long timestampMs) {
		return new EventPolicyRule(
			ruleId,
			effect,
			match,
			reason,
			createdAtMs,
			timestampMs,
			matchCount + 1L,
			source
		);
	}

	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
