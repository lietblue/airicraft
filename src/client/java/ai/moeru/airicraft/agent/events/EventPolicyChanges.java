package ai.moeru.airicraft.agent.events;

import java.util.List;

public record EventPolicyChanges(
	boolean clearAll,
	List<String> removeRuleIds,
	List<EventPolicyRuleUpsert> upserts
) {
	public EventPolicyChanges {
		removeRuleIds = removeRuleIds == null ? List.of() : List.copyOf(removeRuleIds);
		upserts = upserts == null ? List.of() : List.copyOf(upserts);
	}
}
