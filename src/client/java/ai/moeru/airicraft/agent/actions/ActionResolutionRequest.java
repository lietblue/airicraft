package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable input to the deterministic action-route resolution core.
 */
public record ActionResolutionRequest(
	ActionsetIndex actionsets,
	List<ActionFact> facts,
	ActionResolverContext context,
	ActionGoal goal,
	int maxDepth,
	int explorationBudget,
	Set<String> blockedAlternativeKeys,
	boolean preferActionsetRoutes
) {
	public static final int DEFAULT_MAX_DEPTH = 8;
	public static final int DEFAULT_EXPLORATION_BUDGET = 20_000;

	public ActionResolutionRequest {
		actionsets = Objects.requireNonNull(actionsets, "actionsets");
		facts = facts == null ? List.of() : List.copyOf(facts);
		context = Objects.requireNonNull(context, "context");
		goal = Objects.requireNonNull(goal, "goal");
		maxDepth = Math.max(1, maxDepth);
		explorationBudget = Math.max(1, explorationBudget);
		blockedAlternativeKeys = blockedAlternativeKeys == null ? Set.of() : Set.copyOf(blockedAlternativeKeys);
	}

	public static ActionResolutionRequest defaults(
		ActionsetIndex actionsets,
		List<ActionFact> facts,
		ActionResolverContext context,
		ActionGoal goal
	) {
		return new ActionResolutionRequest(
			actionsets,
			facts,
			context,
			goal,
			DEFAULT_MAX_DEPTH,
			DEFAULT_EXPLORATION_BUDGET,
			Set.of(),
			false
		);
	}
}
