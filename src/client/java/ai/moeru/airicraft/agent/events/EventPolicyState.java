package ai.moeru.airicraft.agent.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EventPolicyState {
	private static final int DEFAULT_INTERVENTION_CAP = 64;

	private final int interventionCap;
	private final ArrayList<EventPolicyRule> rules = new ArrayList<>();
	private final ArrayDeque<EventPolicyIntervention> recentInterventions = new ArrayDeque<>();
	private EventPolicyDecision lastDecision;

	public EventPolicyState() {
		this(DEFAULT_INTERVENTION_CAP);
	}

	public EventPolicyState(int interventionCap) {
		this.interventionCap = Math.max(1, interventionCap);
	}

	public EventPolicyDecision evaluate(SemanticEvent event, boolean bypassed) {
		Objects.requireNonNull(event, "event");
		if (bypassed) {
			lastDecision = EventPolicyDecision.bypass();
			return lastDecision;
		}
		for (int index = rules.size() - 1; index >= 0; index--) {
			EventPolicyRule rule = rules.get(index);
			if (!rule.match().matches(event)) {
				continue;
			}
			EventPolicyRule updated = rule.noteMatched(event.timestampMs());
			rules.set(index, updated);
			lastDecision = new EventPolicyDecision(updated.effect(), updated.ruleId(), updated.reason(), false);
			return lastDecision;
		}
		lastDecision = EventPolicyDecision.allow();
		return lastDecision;
	}

	public void upsert(EventPolicyRule rule) {
		Objects.requireNonNull(rule, "rule");
		String ruleId = rule.ruleId();
		if (ruleId != null) {
			removeRuleId(ruleId);
		}
		rules.add(rule);
	}

	public void clear() {
		rules.clear();
		recentInterventions.clear();
		lastDecision = null;
	}

	public void clearInterventions() {
		recentInterventions.clear();
	}

	public void removeRuleIds(List<String> ruleIds) {
		if (ruleIds == null || ruleIds.isEmpty()) {
			return;
		}
		for (String ruleId : ruleIds) {
			removeRuleId(ruleId);
		}
	}

	public int activeRuleCount() {
		return rules.size();
	}

	public int recentInterventionCount() {
		return recentInterventions.size();
	}

	public List<EventPolicyRule> activeRules() {
		return List.copyOf(rules);
	}

	public List<EventPolicyIntervention> recentInterventions() {
		return List.copyOf(recentInterventions);
	}

	public Optional<EventPolicyDecision> lastDecision() {
		return Optional.ofNullable(lastDecision);
	}

	public void recordIntervention(EventPolicyIntervention intervention) {
		Objects.requireNonNull(intervention, "intervention");
		recentInterventions.addLast(intervention);
		while (recentInterventions.size() > interventionCap) {
			recentInterventions.removeFirst();
		}
		lastDecision = new EventPolicyDecision(
			intervention.effect(),
			intervention.matchedRuleId(),
			intervention.reason(),
			false
		);
	}

	private void removeRuleId(String ruleId) {
		if (ruleId == null || ruleId.isBlank()) {
			return;
		}
		rules.removeIf(rule -> ruleId.equals(rule.ruleId()));
	}
}
