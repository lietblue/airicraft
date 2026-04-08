package ai.moeru.airicraft.agent.tasks;

import java.util.Map;

final class CraftRecipeStepExecutor implements StepExecutor {
	private static final long RETRY_COOLDOWN_TICKS = 4L;

	private final CraftingController craftingController;

	private boolean started;
	private String outputItemId;
	private int baselineItemCount;
	private int requestedQuantity;
	private int attemptCount;
	private long nextAttemptTick = -1L;
	private TaskProgressSnapshot lastProgress = new TaskProgressSnapshot(0, 0);
	private StepExecutionResult lastResult = StepExecutionResult.idle();

	CraftRecipeStepExecutor(CraftingController craftingController) {
		this.craftingController = craftingController;
	}

	@Override
	public void begin(LedgerStep step, WorldEvidence evidence, long tick) {
		CraftRecipeStepArgs args = requireArgs(step);
		outputItemId = expectedOutputItemId(step);
		baselineItemCount = outputItemId == null ? 0 : evidence.itemCounts().getOrDefault(outputItemId, 0);
		requestedQuantity = args.quantity();
		attemptCount = 0;
		nextAttemptTick = -1L;
		started = true;
		lastProgress = TaskProgressSnapshot.of(0, requestedQuantity);
		lastResult = new StepExecutionResult(step.id(), StepExecutionStatus.IDLE, null, Map.of(), Map.of(), tick);
	}

	@Override
	public StepExecutorTickResult tick(
		LedgerStep step,
		WorldEvidence evidence,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		boolean nearbyStepTargetsAvailable,
		long tick
	) {
		if (!started) {
			begin(step, evidence, tick);
		}

		CraftRecipeStepArgs args = requireArgs(step);
		String effectiveOutputItemId = outputItemId == null ? expectedOutputItemId(step) : outputItemId;
		int crafted = effectiveOutputItemId == null
			? 0
			: Math.max(0, evidence.itemCounts().getOrDefault(effectiveOutputItemId, 0) - baselineItemCount);
		lastProgress = TaskProgressSnapshot.of(crafted, requestedQuantity);

		if (crafted >= requestedQuantity) {
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.COMPLETED,
				null,
				Map.of(
					"itemId", effectiveOutputItemId,
					"quantity", crafted
				),
				Map.of("reason", "inventory_delta_satisfied"),
				tick
			);
			return new StepExecutorTickResult(TaskState.COMPLETED, null, lastProgress, TaskStep.CRAFT_RECIPE, TaskOwnership.TASK_RUNTIME, lastResult);
		}

		if (!actuationAllowed) {
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.WAITING,
				null,
				Map.of(),
				Map.of("reason", "session_gate_paused"),
				tick
			);
			return new StepExecutorTickResult(TaskState.PAUSED_BY_SESSION_GATE, null, lastProgress, TaskStep.CRAFT_RECIPE, TaskOwnership.TASK_RUNTIME, lastResult);
		}

		if (nextAttemptTick >= 0L && tick < nextAttemptTick) {
			lastResult = new StepExecutionResult(step.id(), StepExecutionStatus.RUNNING, null, Map.of(), Map.of(), tick);
			return new StepExecutorTickResult(TaskState.RUNNING, null, lastProgress, TaskStep.CRAFT_RECIPE, TaskOwnership.TASK_RUNTIME, lastResult);
		}

		CraftingAttemptResult attempt = craftingController.craft(args.recipeId(), false);
		attemptCount++;
		if (!attempt.accepted()) {
			lastResult = new StepExecutionResult(
				step.id(),
				StepExecutionStatus.FAILED,
				attempt.failureReason(),
				Map.of(),
				Map.of("attempts", attemptCount),
				tick
			);
			return new StepExecutorTickResult(TaskState.FAILED, null, lastProgress, TaskStep.NONE, TaskOwnership.NONE, lastResult);
		}

		if (outputItemId == null) {
			outputItemId = attempt.outputItemId();
			baselineItemCount = outputItemId == null ? baselineItemCount : evidence.itemCounts().getOrDefault(outputItemId, 0);
		}
		nextAttemptTick = tick + RETRY_COOLDOWN_TICKS;
		lastResult = new StepExecutionResult(
			step.id(),
			StepExecutionStatus.RUNNING,
			null,
			Map.of(),
			Map.of(
				"attempts", attemptCount,
				"itemId", outputItemId == null ? "" : outputItemId
			),
			tick
		);
		return new StepExecutorTickResult(TaskState.RUNNING, null, lastProgress, TaskStep.CRAFT_RECIPE, TaskOwnership.TASK_RUNTIME, lastResult);
	}

	@Override
	public void cancel(String reason, long tick) {
		lastResult = new StepExecutionResult(null, StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick);
		started = false;
		nextAttemptTick = -1L;
	}

	@Override
	public StepExecutorSnapshot snapshot() {
		return new StepExecutorSnapshot(null, lastProgress, TaskStep.CRAFT_RECIPE, TaskOwnership.TASK_RUNTIME);
	}

	private static CraftRecipeStepArgs requireArgs(LedgerStep step) {
		if (step.args() == null || step.args().craftRecipe() == null) {
			throw new IllegalArgumentException("CRAFT_RECIPE step requires craftRecipe args");
		}
		return step.args().craftRecipe();
	}

	private static String expectedOutputItemId(LedgerStep step) {
		return step.expectedEvidence().stream()
			.filter(requirement -> requirement.type() == EvidenceKind.ITEM_DELTA_AT_LEAST || requirement.type() == EvidenceKind.ITEM_COUNT_AT_LEAST)
			.map(EvidenceRequirement::itemId)
			.filter(itemId -> itemId != null && !itemId.isBlank())
			.findFirst()
			.orElse(null);
	}
}
