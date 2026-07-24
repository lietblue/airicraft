package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private static final int MAX_MINE_DROP_PICKUP_ATTEMPTS = 2;
	private static final double MINE_DROP_PICKUP_RADIUS_BLOCKS = 4.0D;

	private final BaritoneFacade facade;
	private final Supplier<MinecraftClient> clientSupplier;
	private final MineDropObserver mineDropObserver;

	private WorldTaskRequest appliedTask;
	private String terminalEventTaskId;
	private TaskExecutionState terminalEventState;
	private TaskTerminationCause terminalEventCause;
	private String pendingInternalCancelTaskId;
	private String mineDropPickupTaskId;
	private int mineDropPickupAttempts;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this(MinecraftClient::getInstance, facade);
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade) {
		this(clientSupplier, facade, request -> hasMatchingMineDropNearby(clientSupplier.get(), request));
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade, MineDropObserver mineDropObserver) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.facade = Objects.requireNonNull(facade, "facade");
		this.mineDropObserver = Objects.requireNonNull(mineDropObserver, "mineDropObserver");
		this.facade.applySettings();
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (!facade.isLoaded()) {
			if (activeTask.isPresent()) {
				return failUnavailable(activeTask.get());
			}
			reset();
			return Optional.empty();
		}

		if (activeTask.isEmpty()) {
			if (appliedTask != null) {
				pendingInternalCancelTaskId = appliedTask.taskId();
				facade.cancel();
			}
			reset();
			return Optional.empty();
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			if (sessionSnapshot.requiresRespawn() && appliedTask != null) {
				pendingInternalCancelTaskId = appliedTask.taskId();
				facade.cancel();
				appliedTask = null;
			}
			clearTerminalEvent(activeTask.get());
			clearMineDropPickupState();
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				activeTask.get().taskId(),
				activeTask.get().goal(),
				null,
				null,
				null,
				null
			);
			return Optional.empty();
		}

		boolean taskTargetChanged = !sameTaskTarget(activeTask.get(), appliedTask);
		if (taskTargetChanged) {
			if (appliedTask != null) {
				pendingInternalCancelTaskId = appliedTask.taskId();
				facade.cancel();
			}
			clearTerminalEvent(activeTask.get());
			try {
				applyGoal(activeTask.get().goal());
			}
			catch (RuntimeException exception) {
				appliedTask = activeTask.get();
				return failTaskStart(appliedTask, exception);
			}
		}
		appliedTask = activeTask.get();

		Optional<String> pathEvent = facade.pollPathEvent();
		MineDropPickupResult mineDropPickupResult = terminalMineDropPickupEvent(pathEvent, appliedTask);
		if (mineDropPickupResult.handled()) {
			return mineDropPickupResult.event();
		}
		Optional<TerminalOutcome> terminalOutcome = terminalOutcomeFor(pathEvent, appliedTask);
		if (terminalOutcome.isPresent() && isSuppressedInternalCancel(pathEvent)) {
			terminalOutcome = Optional.empty();
		}
		TaskExecutionState state = terminalOutcome
			.map(TerminalOutcome::state)
			.orElseGet(() -> taskTargetChanged || !isTerminal(snapshot.state()) ? TaskExecutionState.RUNNING : snapshot.state());
		TaskTerminationCause terminationCause = terminalOutcome.map(TerminalOutcome::cause).orElse(null);
		snapshot = new TaskExecutionSnapshot(
			state,
			appliedTask.taskId(),
			appliedTask.goal(),
			facade.activeProcessName().orElse(null),
			pathEvent.orElse(null),
			facade.estimatedTicksToGoal().orElse(null),
			terminationCause
		);

		if (terminalOutcome.isEmpty()) {
			return Optional.empty();
		}
		if (Objects.equals(appliedTask.taskId(), terminalEventTaskId)
			&& terminalOutcome.get().state() == terminalEventState
			&& terminalOutcome.get().cause() == terminalEventCause) {
			return Optional.empty();
		}
		terminalEventTaskId = appliedTask.taskId();
		terminalEventState = terminalOutcome.get().state();
		terminalEventCause = terminalOutcome.get().cause();
		return Optional.of(new TaskTerminalEvent(
			appliedTask.taskId(),
			appliedTask.goal(),
			terminalOutcome.get().state(),
			messageFor(terminalOutcome.get().state()),
			terminalOutcome.get().cause()
		));
	}

	private void applyGoal(GoalSnapshot goal) {
		switch (goal.type()) {
			case FOLLOW_PLAYER -> facade.startFollow(goal.targetPlayer());
			case NAVIGATE_TO -> facade.startNavigate(goal.position());
			case MINE_BLOCKS -> {
				MiningToolPreflight.Result result = ensureMiningToolSelected(goal);
				if (!result.ok()) {
					throw new IllegalStateException(result.message());
				}
				facade.startMine(goal.mineSpec());
			}
		}
	}

	private MiningToolPreflight.Result ensureMiningToolSelected(GoalSnapshot goal) {
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null || goal.mineSpec() == null) {
			return MiningToolPreflight.Result.success();
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return MiningToolPreflight.Result.failed("inventory_unavailable_for_tool_selection");
		}
		ArrayList<BlockState> targetStates = new ArrayList<>();
		for (String blockId : goal.mineSpec().blockIds()) {
			Optional<Block> block = resolveBlock(blockId);
			if (block.isEmpty()) {
				return MiningToolPreflight.Result.failed("invalid_block_id " + blockId);
			}
			targetStates.add(block.get().getDefaultState());
		}
		return MiningToolPreflight.ensureSelected(client, player, targetStates);
	}

	private static Optional<Block> resolveBlock(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return Optional.empty();
		}
		Identifier identifier;
		try {
			identifier = Identifier.of(blockId);
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
		return Registries.BLOCK.getOptionalValue(identifier);
	}

	static final class MiningToolPreflight {
		private MiningToolPreflight() {
		}

		static Result ensureSelected(MinecraftClient client, ClientPlayerEntity player, java.util.List<BlockState> targetStates) {
			if (!needsSuitableTool(targetStates)) {
				return Result.success();
			}
			if (isSuitableForAllRequiredBlocks(player.getInventory().getSelectedStack(), targetStates)) {
				return Result.success();
			}
			ScreenHandler handler = player.currentScreenHandler;
			int sourceSlot = findSuitableToolSlot(handler, targetStates);
			if (sourceSlot < 0) {
				return Result.failed("missing_suitable_tool blockIds=" + requiredBlockIds(targetStates));
			}
			int selectedHotbarSlot = player.getInventory().getSelectedSlot();
			if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
				selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			}
			else {
				client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
				selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
			}
			return isSuitableForAllRequiredBlocks(player.getInventory().getSelectedStack(), targetStates)
				? Result.success()
				: Result.failed("tool_selection_failed blockIds=" + requiredBlockIds(targetStates));
		}

		static boolean needsSuitableTool(java.util.List<BlockState> targetStates) {
			return targetStates != null && targetStates.stream().anyMatch(BlockState::isToolRequired);
		}

		static boolean isSuitableForAllRequiredBlocks(ItemStack stack, java.util.List<BlockState> targetStates) {
			if (stack == null || stack.isEmpty()) {
				return false;
			}
			for (BlockState state : targetStates) {
				if (state.isToolRequired() && !stack.isSuitableFor(state)) {
					return false;
				}
			}
			return true;
		}

		private static int findSuitableToolSlot(ScreenHandler handler, java.util.List<BlockState> targetStates) {
			if (!(handler instanceof PlayerScreenHandler)) {
				return -1;
			}
			for (int slot = PlayerScreenHandler.HOTBAR_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
				if (isSuitableForAllRequiredBlocks(handler.getSlot(slot).getStack(), targetStates)) {
					return slot;
				}
			}
			for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_START; slot++) {
				if (isSuitableForAllRequiredBlocks(handler.getSlot(slot).getStack(), targetStates)) {
					return slot;
				}
			}
			return -1;
		}

		private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
			player.getInventory().setSelectedSlot(hotbarSlot);
			if (client.getNetworkHandler() != null) {
				client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
			}
		}

		private static String requiredBlockIds(java.util.List<BlockState> targetStates) {
			return targetStates.stream()
				.filter(BlockState::isToolRequired)
				.map(state -> Registries.BLOCK.getId(state.getBlock()).toString())
				.distinct()
				.toList()
				.toString();
		}

		record Result(boolean ok, String message) {
			static Result success() {
				return new Result(true, "");
			}

			static Result failed(String message) {
				return new Result(false, message);
			}
		}
	}

	private Optional<TaskTerminalEvent> failTaskStart(WorldTaskRequest request, RuntimeException exception) {
		String message = nonEmpty(exception.getMessage(), exception.getClass().getSimpleName());
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			null,
			message,
			null,
			null
		);
		terminalEventTaskId = request.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null
		));
	}

	private Optional<TaskTerminalEvent> failUnavailable(WorldTaskRequest request) {
		String message = "baritone_unavailable";
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			null,
			message,
			null,
			null
		);
		if (Objects.equals(request.taskId(), terminalEventTaskId)
			&& terminalEventState == TaskExecutionState.FAILED
			&& terminalEventCause == null) {
			return Optional.empty();
		}
		terminalEventTaskId = request.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null
		));
	}

	private Optional<TerminalOutcome> terminalOutcomeFor(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty()) {
			return Optional.empty();
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "AT_GOAL" -> Optional.of(new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED));
			case "CALC_FAILED" -> Optional.of(new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
			case "CANCELLED", "CANCELED" -> Optional.of(new TerminalOutcome(cancelledStateFor(activeTask == null ? null : activeTask.goal()), TaskTerminationCause.BARITONE_CANCELLED));
			default -> Optional.empty();
		};
	}

	private MineDropPickupResult terminalMineDropPickupEvent(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty() || activeTask == null || !"AT_GOAL".equals(pathEvent.get().trim().toUpperCase(Locale.ROOT))) {
			return MineDropPickupResult.notHandled();
		}
		if (activeTask.goal() == null || activeTask.goal().type() != GoalType.MINE_BLOCKS || activeTask.pickupSweepPosition() == null) {
			return MineDropPickupResult.notHandled();
		}
		boolean pickupInProgress = Objects.equals(activeTask.taskId(), mineDropPickupTaskId);
		if (!mineDropObserver.hasMatchingNearbyDrop(activeTask)) {
			clearMineDropPickupState();
			return MineDropPickupResult.notHandled();
		}
		if (pickupInProgress && mineDropPickupAttempts >= MAX_MINE_DROP_PICKUP_ATTEMPTS) {
			String message = "nearby_mined_drop_not_collected";
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.FAILED,
				activeTask.taskId(),
				activeTask.goal(),
				facade.activeProcessName().orElse(null),
				message,
				facade.estimatedTicksToGoal().orElse(null),
				null
			);
			terminalEventTaskId = activeTask.taskId();
			terminalEventState = TaskExecutionState.FAILED;
			terminalEventCause = null;
			return MineDropPickupResult.withEvent(new TaskTerminalEvent(activeTask.taskId(), activeTask.goal(), TaskExecutionState.FAILED, message, null));
		}
		mineDropPickupTaskId = activeTask.taskId();
		mineDropPickupAttempts++;
		facade.startNavigate(activeTask.pickupSweepPosition());
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			"pickup_sweep",
			facade.estimatedTicksToGoal().orElse(null),
			null
		);
		return MineDropPickupResult.handledWithoutEvent();
	}

	private static boolean hasMatchingMineDropNearby(MinecraftClient client, WorldTaskRequest request) {
		if (client == null || client.world == null || request == null || request.goal() == null || request.goal().mineSpec() == null || request.pickupSweepPosition() == null) {
			return false;
		}
		GoalPosition position = request.pickupSweepPosition();
		Box area = Box.of(
			Vec3d.ofCenter(new net.minecraft.util.math.BlockPos(position.x(), position.y(), position.z())),
			MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
			MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
			MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D
		);
		java.util.Set<String> matchingItemIds = MinedBlockDropMapper.matchingInventoryItemIds(request.goal().mineSpec().blockIds());
		return !client.world.getEntitiesByClass(ItemEntity.class, area, itemEntity -> {
			ItemStack stack = itemEntity.getStack();
			return stack != null && !stack.isEmpty() && matchingItemIds.contains(Registries.ITEM.getId(stack.getItem()).toString());
		}).isEmpty();
	}

	private void clearMineDropPickupState() {
		mineDropPickupTaskId = null;
		mineDropPickupAttempts = 0;
	}

	private TaskExecutionState cancelledStateFor(GoalSnapshot activeGoal) {
		if (activeGoal == null || activeGoal.type() != GoalType.NAVIGATE_TO || activeGoal.position() == null) {
			return TaskExecutionState.CANCELLED;
		}
		return facade.navigationGoalReached(activeGoal.position())
			? TaskExecutionState.COMPLETED
			: TaskExecutionState.CANCELLED;
	}

	private static boolean isTerminal(TaskExecutionState state) {
		return state == TaskExecutionState.COMPLETED
			|| state == TaskExecutionState.FAILED
			|| state == TaskExecutionState.CANCELLED;
	}

	private boolean isSuppressedInternalCancel(Optional<String> pathEvent) {
		if (pathEvent.isEmpty() || pendingInternalCancelTaskId == null) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!normalized.equals("CANCELLED") && !normalized.equals("CANCELED")) {
			return false;
		}
		pendingInternalCancelTaskId = null;
		return true;
	}

	private static boolean sameTaskTarget(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& sameGoalTarget(left.goal(), right.goal());
	}

	private static boolean sameGoalTarget(GoalSnapshot left, GoalSnapshot right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.type() == right.type()
			&& Objects.equals(left.targetPlayer(), right.targetPlayer())
			&& Objects.equals(left.position(), right.position())
			&& Objects.equals(left.mineSpec(), right.mineSpec());
	}

	private static String messageFor(TaskExecutionState state) {
		return switch (state) {
			case COMPLETED -> "Goal reached";
			case FAILED -> "Path calculation failed";
			case CANCELLED -> "Task cancelled";
			default -> "Task update";
		};
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	@FunctionalInterface
	interface MineDropObserver {
		boolean hasMatchingNearbyDrop(WorldTaskRequest request);
	}

	private record MineDropPickupResult(boolean handled, Optional<TaskTerminalEvent> event) {
		static MineDropPickupResult notHandled() { return new MineDropPickupResult(false, Optional.empty()); }
		static MineDropPickupResult handledWithoutEvent() { return new MineDropPickupResult(true, Optional.empty()); }
		static MineDropPickupResult withEvent(TaskTerminalEvent event) { return new MineDropPickupResult(true, Optional.of(event)); }
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		facade.cancel();
		reset();
	}

	@Override
	public void shutdown() {
		onWorldLeave();
	}

	private void reset() {
		appliedTask = null;
		terminalEventTaskId = null;
		terminalEventState = null;
		terminalEventCause = null;
		pendingInternalCancelTaskId = null;
		clearMineDropPickupState();
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void clearTerminalEvent(WorldTaskRequest task) {
		if (!sameTaskTarget(task, appliedTask) || !Objects.equals(task.taskId(), terminalEventTaskId)) {
			terminalEventTaskId = null;
			terminalEventState = null;
			terminalEventCause = null;
		}
	}

	private record TerminalOutcome(TaskExecutionState state, TaskTerminationCause cause) {
	}
}
