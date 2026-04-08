package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CraftRecipeStepExecutorTest {
	@Test
	void craftRecipeRequestsCraftingAndCompletesOnInventoryDelta() {
		FakeCraftingController controller = new FakeCraftingController(CraftingAttemptResult.started("minecraft:oak_planks"));
		CraftRecipeStepExecutor executor = new CraftRecipeStepExecutor(controller);
		LedgerStep step = new LedgerStep(
			"craft_planks",
			LedgerStepKind.CRAFT_RECIPE,
			new LedgerStepPayload(
				null,
				null,
				null,
				null,
				new CraftRecipeStepArgs("minecraft:oak_planks", 4),
				null,
				null,
				null,
				null,
				null,
				null,
				null
			),
			List.of(),
			LedgerStepStatus.ACTIVE,
			List.of(new EvidenceRequirement(EvidenceKind.ITEM_DELTA_AT_LEAST, null, 4, null, "minecraft:oak_planks", null)),
			1,
			"Turn one log into planks."
		);

		executor.begin(step, evidence(Map.of("minecraft:oak_planks", 0, "minecraft:oak_log", 1), 100L), 100L);
		StepExecutorTickResult started = executor.tick(
			step,
			evidence(Map.of("minecraft:oak_planks", 0, "minecraft:oak_log", 1), 101L),
			TaskExecutionSnapshot.idle(),
			true,
			false,
			101L
		);
		StepExecutorTickResult completed = executor.tick(
			step,
			evidence(Map.of("minecraft:oak_planks", 4), 102L),
			TaskExecutionSnapshot.idle(),
			true,
			false,
			102L
		);

		assertEquals(1, controller.attempts);
		assertEquals(TaskState.RUNNING, started.taskState());
		assertEquals(TaskState.COMPLETED, completed.taskState());
		assertEquals(StepExecutionStatus.COMPLETED, completed.stepResult().status());
		assertEquals("minecraft:oak_planks", completed.stepResult().evidenceDelta().get("itemId"));
		assertEquals(4, completed.stepResult().evidenceDelta().get("quantity"));
		assertNull(completed.currentGoal());
	}

	@Test
	void craftRecipeFailsWhenControllerCannotResolveRecipe() {
		FakeCraftingController controller = new FakeCraftingController(CraftingAttemptResult.failed("recipe_not_found"));
		CraftRecipeStepExecutor executor = new CraftRecipeStepExecutor(controller);
		LedgerStep step = new LedgerStep(
			"craft_planks",
			LedgerStepKind.CRAFT_RECIPE,
			new LedgerStepPayload(
				null,
				null,
				null,
				null,
				new CraftRecipeStepArgs("minecraft:oak_planks", 4),
				null,
				null,
				null,
				null,
				null,
				null,
				null
			),
			List.of(),
			LedgerStepStatus.ACTIVE,
			List.of(new EvidenceRequirement(EvidenceKind.ITEM_DELTA_AT_LEAST, null, 4, null, "minecraft:oak_planks", null)),
			1,
			null
		);

		executor.begin(step, evidence(Map.of("minecraft:oak_log", 1), 100L), 100L);
		StepExecutorTickResult result = executor.tick(
			step,
			evidence(Map.of("minecraft:oak_log", 1), 101L),
			TaskExecutionSnapshot.idle(),
			true,
			false,
			101L
		);

		assertEquals(1, controller.attempts);
		assertEquals(TaskState.FAILED, result.taskState());
		assertEquals("recipe_not_found", result.stepResult().failureReason());
	}

	private static WorldEvidence evidence(Map<String, Integer> itemCounts, long tick) {
		return new WorldEvidence(
			Map.of(),
			itemCounts,
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			"minecraft:air",
			tick
		);
	}

	private static final class FakeCraftingController implements CraftingController {
		private final CraftingAttemptResult result;
		private int attempts;

		private FakeCraftingController(CraftingAttemptResult result) {
			this.result = result;
		}

		@Override
		public CraftingAttemptResult craft(String recipeId, boolean craftAll) {
			attempts++;
			return result;
		}
	}
}
