package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchingWorldTaskExecutorTest {
	@Test
	void attackEntityRequestsRouteToEntityExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.attackEntity(
			"task-1",
			"job-1",
			new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null)
		)));

		assertEquals(WorldTaskType.ATTACK_ENTITY, entityInteraction.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
	}

	@Test
	void pickupEvidenceRoutesToTheDropItemsExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction);

		executor.onPlayerItemPickupObserved(42, "minecraft:oak_log", 1, 2, UUID.randomUUID(), 7L);

		assertEquals(42, dropItems.pickupEntityId);
		assertEquals("minecraft:oak_log", dropItems.pickupItemId);
		assertEquals(1, dropItems.pickupCount);
		assertEquals(2, dropItems.pickupEntityStackCount);
		assertEquals(7L, dropItems.pickupTick);
	}

	@Test
	void entityRequestsResetBaritoneOnlyOnTransition() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"test"
		);
		WorldTaskRequest attack = WorldTaskRequest.attackEntity(
			"attack-task",
			"job-2",
			new EntityInteractionStepArgs(new EntitySelector("slime-1", "Slime", "minecraft:slime"), null)
		);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.direct("nav-task", goal)));
		executor.tick(snapshot(), Optional.of(attack));
		executor.tick(snapshot(), Optional.of(attack));

		assertEquals(2, baritone.calls.size());
		assertEquals(WorldTaskType.NAVIGATE, baritone.calls.get(0).orElseThrow().type());
		assertEquals(Optional.empty(), baritone.calls.get(1));
	}

	@Test
	void useEntityRequestsRouteToEntityExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction, smelting);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.useEntity(
			"task-2",
			"job-2",
			new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears")
		)));

		assertEquals(WorldTaskType.USE_ENTITY, entityInteraction.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
	}

	@Test
	void smeltingRequestsRouteToSmeltingExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction, smelting);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.smeltItems(
			"task-3",
			"job-3",
			new SmeltItemsStepArgs("smelt:iron:nearby-1", 2, SmeltingFuelMode.AUTO, null, 0, null)
		)));

		assertEquals(WorldTaskType.SMELT_ITEMS, smelting.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
		assertEquals(Optional.empty(), entityInteraction.lastTask);
	}

	@Test
	void collectSmeltedRequestsRouteToSmeltingExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction, smelting);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.collectSmeltedItems(
			"task-4",
			"job-4",
			new CollectSmeltedItemsStepArgs("smelt-process-1", "confirm-1")
		)));

		assertEquals(WorldTaskType.COLLECT_SMELTED_ITEMS, smelting.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
		assertEquals(Optional.empty(), entityInteraction.lastTask);
	}

	@Test
	void returnToSurfaceRequestsRouteToReturnExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		RecordingExecutor returnToSurface = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(
			baritone,
			crafting,
			dropItems,
			entityInteraction,
			smelting,
			returnToSurface
		);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.returnToSurface(
			"task-5",
			"job-5",
			new ReturnToSurfaceStepArgs(new GoalPosition(0, 70, 0, false), "nearest_surface", true, List.of("minecraft:dirt"))
		)));

		assertEquals(WorldTaskType.RETURN_TO_SURFACE, returnToSurface.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
		assertEquals(Optional.empty(), entityInteraction.lastTask);
		assertEquals(Optional.empty(), smelting.lastTask);
	}

	@Test
	void blockInteractionRequestsRouteToBlockExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		RecordingExecutor returnToSurface = new RecordingExecutor();
		RecordingExecutor blockInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(
			baritone,
			crafting,
			dropItems,
			entityInteraction,
			smelting,
			returnToSurface,
			blockInteraction
		);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.useBlock(
			"task-6",
			"job-6",
			new BlockUseStepArgs("minecraft:wheat_seeds", new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
		)));

		assertEquals(WorldTaskType.USE_BLOCK, blockInteraction.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
		assertEquals(Optional.empty(), entityInteraction.lastTask);
		assertEquals(Optional.empty(), smelting.lastTask);
		assertEquals(Optional.empty(), returnToSurface.lastTask);
	}

	@Test
	void blockBreakRequestsRouteToBlockBreakExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		RecordingExecutor returnToSurface = new RecordingExecutor();
		RecordingExecutor blockInteraction = new RecordingExecutor();
		RecordingExecutor blockBreak = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(
			baritone,
			crafting,
			dropItems,
			entityInteraction,
			smelting,
			returnToSurface,
			blockInteraction,
			blockBreak
		);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.breakBlocks(
			"task-7",
			"job-7",
			new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
				new GoalPosition(1, 64, 2, true),
				List.of("minecraft:grass_block")
			)))
		)));

		assertEquals(WorldTaskType.BREAK_BLOCKS, blockBreak.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
		assertEquals(Optional.empty(), entityInteraction.lastTask);
		assertEquals(Optional.empty(), smelting.lastTask);
		assertEquals(Optional.empty(), returnToSurface.lastTask);
		assertEquals(Optional.empty(), blockInteraction.lastTask);
	}

	@Test
	void underwaterHarvestRequestsRouteThroughMiningCoordinator() {
		RecordingExecutor miningCoordinator = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		RecordingExecutor returnToSurface = new RecordingExecutor();
		RecordingExecutor blockInteraction = new RecordingExecutor();
		RecordingExecutor blockBreak = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(
			miningCoordinator,
			crafting,
			dropItems,
			entityInteraction,
			smelting,
			returnToSurface,
			blockInteraction,
			blockBreak
		);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:seagrass"), 20, List.of("minecraft:seagrass"), List.of("minecraft:shears")),
			1L,
			"action_graph"
		);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.underwaterHarvest(
			"harvest-1",
			"job-1",
			goal,
			new UnderwaterHarvestStepArgs(new GoalPosition(2, 52, -4, true))
		)));

		assertEquals(WorldTaskType.UNDERWATER_HARVEST, miningCoordinator.lastTask.orElseThrow().type());
	}

	@Test
	void taskTypeTransitionWaitsForSharedBaritoneOwnershipAndReceipt() {
		RecordingExecutor mining = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		RecordingExecutor smelting = new RecordingExecutor();
		RecordingExecutor returnToSurface = new RecordingExecutor();
		RecordingExecutor blockInteraction = new RecordingExecutor();
		RecordingExecutor blockBreak = new RecordingExecutor();
		RecordingBaritone sharedBaritone = new RecordingBaritone();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(
			mining,
			crafting,
			dropItems,
			entityInteraction,
			smelting,
			returnToSurface,
			blockInteraction,
			blockBreak,
			sharedBaritone
		);
		GoalSnapshot navigation = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(2, 64, 3, true),
			null,
			0L,
			"test"
		);
		executor.tick(snapshot(), Optional.of(WorldTaskRequest.direct("nav", navigation)));
		sharedBaritone.active = true;
		WorldTaskRequest craft = WorldTaskRequest.craftRecipe(
			"craft",
			"job",
			new CraftRecipeStepArgs("minecraft:stick", 1)
		);

		assertTrue(executor.tick(snapshot(), Optional.of(craft)).isEmpty());
		assertTrue(crafting.lastTask.isEmpty());
		assertEquals("waiting_for_previous_baritone_release", executor.snapshot().lastPathEvent());

		sharedBaritone.cancellationPending = false;
		executor.tick(snapshot(), Optional.of(craft));

		assertEquals(WorldTaskType.CRAFT_RECIPE, crafting.lastTask.orElseThrow().type());
		assertEquals(1, sharedBaritone.cancelCalls);
	}

	private static SessionSnapshot snapshot() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 0L);
	}

	private static final class RecordingExecutor implements WorldTaskExecutor {
		private Optional<WorldTaskRequest> lastTask = Optional.empty();
		private final List<Optional<WorldTaskRequest>> calls = new ArrayList<>();
		private int pickupEntityId;
		private String pickupItemId;
		private int pickupCount;
		private int pickupEntityStackCount;
		private long pickupTick;

		@Override
		public void onPlayerItemPickupObserved(
			int entityId,
			String itemId,
			int pickedUpCount,
			int entityStackCount,
			UUID collectorIdentity,
			long observedAtTick
		) {
			pickupEntityId = entityId;
			pickupItemId = itemId;
			pickupCount = pickedUpCount;
			pickupEntityStackCount = entityStackCount;
			pickupTick = observedAtTick;
		}

		@Override
		public Optional<TaskTerminalEvent> tick(ai.moeru.airicraft.agent.session.SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastTask = activeTask;
			calls.add(activeTask);
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}

	private static final class RecordingBaritone implements BaritoneFacade {
		private boolean active;
		private boolean cancellationPending;
		private int cancelCalls;

		@Override public boolean isLoaded() { return true; }
		@Override public void applySettings() { }
		@Override public double walkOnWaterPenalty() { return 0.0D; }
		@Override public void setWalkOnWaterPenalty(double value) { }
		@Override public void startFollow(String playerName) { }
		@Override public void startNavigate(GoalPosition position) { }
		@Override public void startNavigateNear(GoalPosition position, int radiusBlocks) { }
		@Override public void startMine(GoalMineSpec spec) { }
		@Override public boolean mineProcessActive() { return active; }
		@Override public boolean processActive() { return active; }

		@Override
		public boolean cancel() {
			if (active && !cancellationPending) {
				cancelCalls++;
				active = false;
				cancellationPending = true;
			}
			return cancellationPending;
		}

		@Override public boolean cancellationPending() { return cancellationPending; }
		@Override public Optional<String> activeProcessName() { return Optional.empty(); }
		@Override public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		@Override public Optional<String> pollPathEvent() { return Optional.empty(); }
		@Override public boolean navigationGoalReached(GoalPosition position) { return false; }
	}
}
