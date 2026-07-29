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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private static final int MAX_MINE_DROP_PICKUP_ATTEMPTS_PER_TARGET = 2;
	private static final int MAX_MINE_DROP_PICKUP_SETTLE_TICKS = 10;
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
	private MineDropTarget mineDropPickupTarget;
	private int mineDropPickupAttempts;
	private int mineDropPickupSettleTicks;
	private TerminalOutcome pendingMineTerminalOutcome;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this(MinecraftClient::getInstance, facade);
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade) {
		this(clientSupplier, facade, request -> matchingMineDropsNearby(clientSupplier.get(), request));
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
		boolean mineGoalJustSatisfied = mineGoalJustSatisfied(activeTask.get(), appliedTask);
		if (taskTargetChanged) {
			clearMineDropPickupState();
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
		else if (mineGoalJustSatisfied) {
			clearTerminalEvent(activeTask.get());
			facade.cancel();
		}
		appliedTask = activeTask.get();

		Optional<String> pathEvent = facade.pollPathEvent();
		if (isSuppressedInternalCancel(pathEvent)) {
			pathEvent = Optional.empty();
		}
		boolean mineProcessOwnsPathEvent = mineProcessOwnsPathEvent(pathEvent, appliedTask);
		MineDropPickupResult mineDropPickupResult = mineProcessOwnsPathEvent
			? MineDropPickupResult.notHandled()
			: terminalMineDropPickupEvent(pathEvent, appliedTask);
		if (mineDropPickupResult.handled()) {
			return mineDropPickupResult.event();
		}
		if (continueFollow(pathEvent, appliedTask)) {
			return Optional.empty();
		}
		Optional<TerminalOutcome> terminalOutcome = terminalOutcomeFor(pathEvent, appliedTask);
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
			ScreenHandler handler = player.currentScreenHandler;
			ItemStack selectedStack = player.getInventory().getSelectedStack();
			int sourceSlot = findPreferredToolSlot(handler, selectedStack, targetStates);
			if (sourceSlot < 0) {
				return needsSuitableTool(targetStates) && !isSuitableForAllRequiredBlocks(selectedStack, targetStates)
					? Result.failed("missing_suitable_tool blockIds=" + requiredBlockIds(targetStates))
					: Result.success();
			}
			int selectedHotbarSlot = player.getInventory().getSelectedSlot();
			if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
				selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			}
			else {
				client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
				selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
			}
			return !needsSuitableTool(targetStates) || isSuitableForAllRequiredBlocks(player.getInventory().getSelectedStack(), targetStates)
				? Result.success()
				: Result.failed("tool_selection_failed blockIds=" + requiredBlockIds(targetStates));
		}

		static boolean needsSuitableTool(java.util.List<BlockState> targetStates) {
			return targetStates != null && targetStates.stream().anyMatch(BlockState::isToolRequired);
		}

		static boolean isSuitableForAllRequiredBlocks(ItemStack stack, java.util.List<BlockState> targetStates) {
			if (targetStates == null || targetStates.isEmpty()) {
				return true;
			}
			for (BlockState state : targetStates) {
				if (state.isToolRequired() && (stack == null || stack.isEmpty() || !stack.isSuitableFor(state))) {
					return false;
				}
			}
			return true;
		}

		private static int findPreferredToolSlot(ScreenHandler handler, ItemStack selectedStack, java.util.List<BlockState> targetStates) {
			if (!(handler instanceof PlayerScreenHandler)) {
				return -1;
			}
			ItemStack bestStack = selectedStack == null ? ItemStack.EMPTY : selectedStack;
			int bestSlot = -1;
			for (int slot = PlayerScreenHandler.HOTBAR_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
				ItemStack candidate = handler.getSlot(slot).getStack();
				if (isBetterMiningTool(candidate, bestStack, targetStates)) {
					bestStack = candidate;
					bestSlot = slot;
				}
			}
			for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_START; slot++) {
				ItemStack candidate = handler.getSlot(slot).getStack();
				if (isBetterMiningTool(candidate, bestStack, targetStates)) {
					bestStack = candidate;
					bestSlot = slot;
				}
			}
			return bestSlot;
		}

		static boolean isBetterMiningTool(ItemStack candidate, ItemStack current, java.util.List<BlockState> targetStates) {
			if (candidate == null || candidate.isEmpty()) {
				return false;
			}
			return isBetterMiningTool(toolScore(candidate, targetStates), toolScore(current, targetStates));
		}

		static boolean isBetterMiningTool(ToolScore candidate, ToolScore current) {
			return candidate != null
				&& candidate.eligible()
				&& (current == null || !current.eligible() || candidate.speed() > current.speed());
		}

		private static ToolScore toolScore(ItemStack stack, java.util.List<BlockState> targetStates) {
			boolean eligible = isSuitableForAllRequiredBlocks(stack, targetStates);
			return new ToolScore(eligible, miningSpeed(stack, targetStates));
		}

		private static float miningSpeed(ItemStack stack, java.util.List<BlockState> targetStates) {
			if (stack == null || stack.isEmpty() || targetStates == null || targetStates.isEmpty()) {
				return 1.0F;
			}
			float speed = Float.MAX_VALUE;
			for (BlockState state : targetStates) {
				speed = Math.min(speed, stack.getMiningSpeedMultiplier(state));
			}
			return speed == Float.MAX_VALUE ? 1.0F : speed;
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

		record ToolScore(boolean eligible, float speed) {
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
			case "AT_GOAL" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED));
			case "CALC_FAILED" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
			case "CANCELLED", "CANCELED" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(cancelledOutcomeFor(activeTask));
			default -> Optional.empty();
		};
	}

	private boolean continueFollow(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty()
			|| activeTask == null
			|| activeTask.goal() == null
			|| activeTask.goal().type() != GoalType.FOLLOW_PLAYER) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!"AT_GOAL".equals(normalized)
			&& !"CALC_FAILED".equals(normalized)
			&& !"CANCELLED".equals(normalized)
			&& !"CANCELED".equals(normalized)) {
			return false;
		}
		String pathState = normalized;
		if (!"AT_GOAL".equals(normalized)) {
			facade.startFollow(activeTask.goal().targetPlayer());
			pathState = "FOLLOW_REACQUIRING";
		}
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			pathState,
			facade.estimatedTicksToGoal().orElse(null),
			null
		);
		return true;
	}

	private boolean mineProcessOwnsPathEvent(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (
			pathEvent.isEmpty()
				|| activeTask == null
				|| activeTask.goal() == null
				|| activeTask.goal().type() != GoalType.MINE_BLOCKS
		) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			// MineProcess uses path goals per selected block. It owns target completion,
			// target blacklisting after CALC_FAILED, and reselection until it deactivates.
			case "AT_GOAL", "CALC_FAILED", "CANCELLED", "CANCELED" -> facade.mineProcessActive();
			default -> false;
		};
	}

	private MineDropPickupResult terminalMineDropPickupEvent(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		boolean pickupInProgress = activeTask != null
			&& Objects.equals(activeTask.taskId(), mineDropPickupTaskId)
			&& pendingMineTerminalOutcome != null;
		Optional<TerminalOutcome> currentOutcome = terminalOutcomeFor(pathEvent, activeTask);
		if ((!pickupInProgress && currentOutcome.isEmpty()) || activeTask == null) {
			return MineDropPickupResult.notHandled();
		}
		if (activeTask.goal() == null || activeTask.goal().type() != GoalType.MINE_BLOCKS || activeTask.pickupSweepPositions().isEmpty()) {
			return MineDropPickupResult.notHandled();
		}
		if (!pickupInProgress) {
			mineDropPickupTaskId = activeTask.taskId();
			mineDropPickupTarget = null;
			mineDropPickupAttempts = 0;
			mineDropPickupSettleTicks = 0;
			pendingMineTerminalOutcome = currentOutcome.orElseThrow();
		}

		List<MineDropTarget> matchingDrops = mineDropObserver.matchingNearbyDrops(activeTask);
		if (matchingDrops == null || matchingDrops.isEmpty()) {
			if (!pickupInProgress) {
				clearMineDropPickupState();
				return MineDropPickupResult.notHandled();
			}
			return finishMineDropPickup(activeTask);
		}

		MineDropTarget nextTarget = matchingDrops.stream()
			.filter(target -> mineDropPickupTarget != null && target.entityId() == mineDropPickupTarget.entityId())
			.findFirst()
			.orElse(matchingDrops.getFirst());
		boolean sameTarget = mineDropPickupTarget != null && nextTarget.entityId() == mineDropPickupTarget.entityId();
		if (sameTarget && currentOutcome.isEmpty() && mineDropPickupSettleTicks == 0) {
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
		if (sameTarget && mineDropPickupAttempts >= MAX_MINE_DROP_PICKUP_ATTEMPTS_PER_TARGET) {
			mineDropPickupSettleTicks++;
			if (mineDropPickupSettleTicks > MAX_MINE_DROP_PICKUP_SETTLE_TICKS) {
				return failMineDropPickup(activeTask);
			}
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.RUNNING,
				activeTask.taskId(),
				activeTask.goal(),
				facade.activeProcessName().orElse(null),
				"pickup_settle",
				facade.estimatedTicksToGoal().orElse(null),
				null
			);
			return MineDropPickupResult.handledWithoutEvent();
		}
		if (!sameTarget) {
			mineDropPickupTarget = nextTarget;
			mineDropPickupAttempts = 0;
			mineDropPickupSettleTicks = 0;
		}
		mineDropPickupAttempts++;
		pendingInternalCancelTaskId = activeTask.taskId();
		facade.startNavigate(nextTarget.position());
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

	private MineDropPickupResult finishMineDropPickup(WorldTaskRequest activeTask) {
		TerminalOutcome outcome = pendingMineTerminalOutcome;
		clearMineDropPickupState();
		snapshot = new TaskExecutionSnapshot(
			outcome.state(),
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			messageFor(outcome.state()),
			facade.estimatedTicksToGoal().orElse(null),
			outcome.cause()
		);
		terminalEventTaskId = activeTask.taskId();
		terminalEventState = outcome.state();
		terminalEventCause = outcome.cause();
		return MineDropPickupResult.withEvent(new TaskTerminalEvent(
			activeTask.taskId(),
			activeTask.goal(),
			outcome.state(),
			messageFor(outcome.state()),
			outcome.cause()
		));
	}

	private TerminalOutcome cancelledOutcomeFor(WorldTaskRequest activeTask) {
		if (
			activeTask != null
				&& activeTask.mineGoalSatisfied()
				&& activeTask.goal() != null
				&& activeTask.goal().type() == GoalType.MINE_BLOCKS
		) {
			return new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED);
		}
		return new TerminalOutcome(
			cancelledStateFor(activeTask == null ? null : activeTask.goal()),
			TaskTerminationCause.BARITONE_CANCELLED
		);
	}

	private MineDropPickupResult failMineDropPickup(WorldTaskRequest activeTask) {
		String message = "nearby_mined_drop_not_collected";
		clearMineDropPickupState();
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

	private static List<MineDropTarget> matchingMineDropsNearby(MinecraftClient client, WorldTaskRequest request) {
		if (client == null || client.world == null || client.player == null || request == null || request.goal() == null || request.goal().mineSpec() == null || request.pickupSweepPositions().isEmpty()) {
			return List.of();
		}
		Set<String> matchingItemIds = MinedBlockDropMapper.matchingInventoryItemIds(request.goal().mineSpec().blockIds());
		Set<Integer> seenEntityIds = new HashSet<>();
		ArrayList<ItemEntity> matchingDrops = new ArrayList<>();
		for (GoalPosition position : request.pickupSweepPositions()) {
			Box area = Box.of(
				Vec3d.ofCenter(new BlockPos(position.x(), position.y(), position.z())),
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D
			);
			for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, area, entity -> isMatchingMineDrop(entity, matchingItemIds))) {
				if (seenEntityIds.add(itemEntity.getId())) {
					matchingDrops.add(itemEntity);
				}
			}
		}
		matchingDrops.sort(Comparator.comparingDouble(itemEntity -> itemEntity.squaredDistanceTo(client.player)));
		return matchingDrops.stream()
			.map(itemEntity -> {
				BlockPos position = itemEntity.getBlockPos();
				return new MineDropTarget(itemEntity.getId(), new GoalPosition(position.getX(), position.getY(), position.getZ(), true));
			})
			.toList();
	}

	private static boolean isMatchingMineDrop(ItemEntity itemEntity, Set<String> matchingItemIds) {
		ItemStack stack = itemEntity == null ? ItemStack.EMPTY : itemEntity.getStack();
		return stack != null && !stack.isEmpty() && matchingItemIds.contains(Registries.ITEM.getId(stack.getItem()).toString());
	}

	private void clearMineDropPickupState() {
		mineDropPickupTaskId = null;
		mineDropPickupTarget = null;
		mineDropPickupAttempts = 0;
		mineDropPickupSettleTicks = 0;
		pendingMineTerminalOutcome = null;
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
		if (!isCancelledPathEvent(normalized)) {
			return false;
		}
		pendingInternalCancelTaskId = null;
		return true;
	}

	private static boolean isCancelledPathEvent(String normalizedPathEvent) {
		return "CANCELLED".equals(normalizedPathEvent) || "CANCELED".equals(normalizedPathEvent);
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

	private static boolean mineGoalJustSatisfied(WorldTaskRequest current, WorldTaskRequest previous) {
		return current != null
			&& current.mineGoalSatisfied()
			&& (previous == null || !previous.mineGoalSatisfied())
			&& current.goal() != null
			&& current.goal().type() == GoalType.MINE_BLOCKS;
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
		List<MineDropTarget> matchingNearbyDrops(WorldTaskRequest request);
	}

	record MineDropTarget(int entityId, GoalPosition position) {
		MineDropTarget {
			Objects.requireNonNull(position, "position");
		}
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
