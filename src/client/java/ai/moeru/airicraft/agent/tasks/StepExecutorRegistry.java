package ai.moeru.airicraft.agent.tasks;

import java.util.EnumMap;

final class StepExecutorRegistry {
	private final EnumMap<LedgerStepKind, StepExecutor> executors = new EnumMap<>(LedgerStepKind.class);

	StepExecutorRegistry() {
		this(new LiveCraftingController());
	}

	StepExecutorRegistry(CraftingController craftingController) {
		executors.put(LedgerStepKind.COLLECT_RESOURCE, new CollectionStepExecutor());
		executors.put(LedgerStepKind.CRAFT_RECIPE, new CraftRecipeStepExecutor(craftingController));
		executors.put(LedgerStepKind.WAIT, new WaitStepExecutor());
		executors.put(LedgerStepKind.ASK_USER, new AskUserStepExecutor());
		executors.put(LedgerStepKind.FINISH, new FinishStepExecutor());
		for (LedgerStepKind kind : LedgerStepKind.values()) {
			executors.putIfAbsent(kind, new UnsupportedStepExecutor(kind));
		}
	}

	StepExecutor executorFor(LedgerStep step) {
		return executors.get(step.kind());
	}
}
