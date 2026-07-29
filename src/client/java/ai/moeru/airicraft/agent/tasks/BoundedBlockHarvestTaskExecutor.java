package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Harvests exact loaded source blocks inside a fixed local boundary. It owns
 * underwater approach and breathing; Baritone is used only to reach a safe
 * nearby position while the player is still breathing.
 */
public final class BoundedBlockHarvestTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final double DIRECT_APPROACH_RANGE_SQUARED = 64.0D;
	private static final int NAVIGATION_RADIUS_BLOCKS = 2;
	private static final int BREAK_TIMEOUT_TICKS = 200;
	private static final int PICKUP_TIMEOUT_TICKS = 80;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritone;
	private final CameraController camera;
	private final MovementController movement = new MovementController();

	private WorldTaskRequest appliedTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private boolean terminalEventEmitted;
	private BlockPos searchOrigin;
	private List<HarvestTarget> batch = List.of();
	private int batchCursor;
	private BlockPos breakingTarget;
	private long breakStartedTick = -1L;
	private int pickupTicksRemaining = -1;
	private final Set<UUID> unreachableDropIds = new HashSet<>();
	private int inventoryBeforeBreak;
	private boolean navigationStarted;
	private boolean surfacing;
	private int harvestedBlocks;

	public BoundedBlockHarvestTaskExecutor(BaritoneFacade baritone, CameraController camera) {
		this(MinecraftClient::getInstance, baritone, camera);
	}

	BoundedBlockHarvestTaskExecutor(
		Supplier<MinecraftClient> clientSupplier,
		BaritoneFacade baritone,
		CameraController camera
	) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritone = baritone;
		this.camera = camera == null ? new CameraController() : camera;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.BOUNDED_HARVEST) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return fail(request, "world_unavailable");
		}
		if (request.goal() == null || request.goal().mineSpec() == null) {
			return fail(request, "unsupported_acquisition_method missing_mine_spec");
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return fail(request, "interaction_busy");
		}

		GoalMineSpec spec = request.goal().mineSpec();
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (BoundedHarvestPolicy.terminalDecision(inventoryCount, spec.quantity(), 1)
			== BoundedHarvestPolicy.TerminalDecision.COMPLETE) {
			return complete(request, "bounded_harvest_succeeded itemCount=" + inventoryCount + " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks);
		}
		if (!selectRequiredTool(client, player, spec.requiredToolItemIds())) {
			return fail(request, "unsupported_acquisition_method missing_required_tool requiredToolItemIds=" + spec.requiredToolItemIds());
		}

		long tick = sessionSnapshot.tickCount();
		if (surfacing || BoundedHarvestPolicy.shouldSurface(player.isSubmergedInWater(), player.getAir(), player.getMaxAir())) {
			return tickSurfacing(request, client, player, tick);
		}
		if (searchOrigin == null) {
			searchOrigin = player.getBlockPos().toImmutable();
		}
		if (pickupTicksRemaining >= 0) {
			Optional<TaskTerminalEvent> pickup = tickPickup(request, client, player, spec, tick);
			if (pickup.isPresent() || pickupTicksRemaining >= 0) {
				return pickup;
			}
		}
		if (batchCursor >= batch.size()) {
			batch = selectBatch(client, spec, searchOrigin);
			batchCursor = 0;
			if (batch.isEmpty()) {
				return fail(request, "resource_not_found_nearby radius=" + BoundedHarvestPolicy.HORIZONTAL_RADIUS
					+ " verticalRadius=" + BoundedHarvestPolicy.VERTICAL_RADIUS + " blockIds=" + spec.blockIds()
					+ " itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			}
		}

		HarvestTarget target = batch.get(batchCursor);
		BlockState currentState = client.world.getBlockState(target.pos());
		if (!spec.blockIds().contains(blockId(currentState))) {
			clearBreak(client);
			batchCursor++;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_reassess targetPos=" + compactPos(target.pos()));
			return Optional.empty();
		}
		if (target.environment().underwater()
			&& BoundedHarvestPolicy.shouldSurface(player.isSubmergedInWater(), player.getAir(), player.getMaxAir())) {
			return tickSurfacing(request, client, player, tick);
		}

		Vec3d targetCenter = Vec3d.ofCenter(target.pos());
		double distanceSquared = player.getEyePos().squaredDistanceTo(targetCenter);
		if (distanceSquared > INTERACTION_RANGE_SQUARED) {
			return tickApproach(request, client, player, target, targetCenter, distanceSquared, tick);
		}
		cancelNavigation();
		movement.stop(client);
		camera.lookAtNow(client, targetCenter);
		if (breakingTarget == null) {
			if (!client.interactionManager.attackBlock(target.pos(), Direction.UP)) {
				return fail(request, "break_start_failed targetPos=" + compactPos(target.pos()));
			}
			breakingTarget = target.pos();
			breakStartedTick = tick;
			inventoryBeforeBreak = inventoryCount;
		}
		if (tick - breakStartedTick > BREAK_TIMEOUT_TICKS) {
			return fail(request, "break_timeout targetPos=" + compactPos(target.pos()));
		}
		client.interactionManager.updateBlockBreakingProgress(target.pos(), Direction.UP);
		player.swingHand(Hand.MAIN_HAND);
		if (!spec.blockIds().contains(blockId(client.world.getBlockState(target.pos())))) {
			harvestedBlocks++;
			clearBreak(client);
			batchCursor++;
			pickupTicksRemaining = PICKUP_TIMEOUT_TICKS;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_broken targetPos=" + compactPos(target.pos())
				+ " environment=" + target.environment().name().toLowerCase());
			return Optional.empty();
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "breaking targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAir());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickApproach(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		HarvestTarget target,
		Vec3d targetCenter,
		double distanceSquared,
		long tick
	) {
		if (!player.isSubmergedInWater()
			&& distanceSquared > DIRECT_APPROACH_RANGE_SQUARED
			&& baritone != null
			&& baritone.isLoaded()) {
			movement.stop(client);
			if (!navigationStarted) {
				baritone.startNavigateNear(goalPosition(target.pos()), NAVIGATION_RADIUS_BLOCKS);
				navigationStarted = true;
			}
			Optional<String> event = baritone.pollPathEvent();
			if (event.isPresent() && ("CALC_FAILED".equalsIgnoreCase(event.get())
				|| "CANCELLED".equalsIgnoreCase(event.get()) || "CANCELED".equalsIgnoreCase(event.get()))) {
				cancelNavigation();
			}
			else {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_local_target_with_baritone targetPos=" + compactPos(target.pos()));
				return Optional.empty();
			}
		}
		cancelNavigation();
		camera.lookAtNow(client, targetCenter);
		boolean jump = !player.isSubmergedInWater() && player.horizontalCollision;
		movement.moveDirectional(client, true, false, false, false, true, jump, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_exact_target targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAir());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickSurfacing(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		long tick
	) {
		if (!surfacing) {
			cancelNavigation();
			clearBreak(client);
			surfacing = true;
		}
		if (BoundedHarvestPolicy.mayResumeHarvest(player.isSubmergedInWater(), player.getAir(), player.getMaxAir())) {
			movement.stop(client);
			surfacing = false;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "air_replenished air=" + player.getAir());
			return Optional.empty();
		}
		camera.lookAtNow(client, player.getEyePos().add(0.0D, 8.0D, 0.0D));
		movement.swimUp(client, false, false, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "surfacing_for_air air=" + player.getAir()
			+ " reserve=" + BoundedHarvestPolicy.AIR_RESERVE_TICKS);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickPickup(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		GoalMineSpec spec,
		long tick
	) {
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (inventoryCount >= spec.quantity()) {
			pickupTicksRemaining = -1;
			return complete(request, "bounded_harvest_succeeded itemCount=" + inventoryCount + " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks);
		}
		Optional<ItemEntity> drop = nearestMatchingDrop(client, player, spec.matchingItemIds(), unreachableDropIds);
		BoundedHarvestPolicy.PickupDecision decision = BoundedHarvestPolicy.pickupDecision(
			inventoryBeforeBreak,
			inventoryCount,
			pickupTicksRemaining,
			drop.isPresent()
		);
		if (decision == BoundedHarvestPolicy.PickupDecision.COLLECTED) {
			movement.stop(client);
			pickupTicksRemaining = -1;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_collected itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		if (decision == BoundedHarvestPolicy.PickupDecision.UNREACHABLE) {
			movement.stop(client);
			drop.ifPresent(entity -> unreachableDropIds.add(entity.getUuid()));
			pickupTicksRemaining = -1;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_unreachable itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		pickupTicksRemaining--;
		if (decision == BoundedHarvestPolicy.PickupDecision.APPROACH) {
			Vec3d itemPos = drop.orElseThrow().getPos();
			BoundedHarvestPolicy.PickupTarget blockCenter = BoundedHarvestPolicy.pickupTarget(
				itemPos.x,
				itemPos.y,
				itemPos.z
			);
			Vec3d target = new Vec3d(blockCenter.x(), blockCenter.y(), blockCenter.z());
			camera.lookAtNow(client, target);
			movement.moveDirectional(client, true, false, false, false, false, false, tick);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "collecting_drop itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		movement.stop(client);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_drop itemCount=" + inventoryCount);
		return Optional.empty();
	}

	private static List<HarvestTarget> selectBatch(MinecraftClient client, GoalMineSpec spec, BlockPos origin) {
		Set<String> blockIds = new HashSet<>(spec.blockIds());
		ArrayList<BoundedHarvestPolicy.Target> candidates = new ArrayList<>();
		for (int x = origin.getX() - BoundedHarvestPolicy.HORIZONTAL_RADIUS; x <= origin.getX() + BoundedHarvestPolicy.HORIZONTAL_RADIUS; x++) {
			for (int z = origin.getZ() - BoundedHarvestPolicy.HORIZONTAL_RADIUS; z <= origin.getZ() + BoundedHarvestPolicy.HORIZONTAL_RADIUS; z++) {
				for (int y = origin.getY() - BoundedHarvestPolicy.VERTICAL_RADIUS; y <= origin.getY() + BoundedHarvestPolicy.VERTICAL_RADIUS; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!client.world.isInBuildLimit(pos) || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					BlockState state = client.world.getBlockState(pos);
					String blockId = blockId(state);
					if (!blockIds.contains(blockId)) {
						continue;
					}
					boolean containsFluid = !state.getFluidState().isEmpty();
					boolean adjacentFluid = false;
					for (Direction direction : Direction.values()) {
						if (!client.world.getFluidState(pos.offset(direction)).isEmpty()) {
							adjacentFluid = true;
							break;
						}
					}
					candidates.add(new BoundedHarvestPolicy.Target(
						new BoundedHarvestPolicy.Position(x, y, z),
						blockId,
						BoundedHarvestPolicy.classify(containsFluid, adjacentFluid)
					));
				}
			}
		}
		BoundedHarvestPolicy.Position policyOrigin = new BoundedHarvestPolicy.Position(origin.getX(), origin.getY(), origin.getZ());
		return BoundedHarvestPolicy.selectBatch(candidates, policyOrigin).stream()
			.map(target -> new HarvestTarget(
				new BlockPos(target.position().x(), target.position().y(), target.position().z()),
				target.blockId(),
				target.environment()
			))
			.toList();
	}

	private static Optional<ItemEntity> nearestMatchingDrop(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<String> matchingItemIds,
		Set<UUID> excludedDropIds
	) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		return client.world.getEntitiesByClass(
			ItemEntity.class,
			new Box(player.getBlockPos()).expand(8.0D),
			entity -> ids.contains(itemId(entity.getStack())) && !excludedDropIds.contains(entity.getUuid())
		).stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo));
	}

	private static int matchingInventoryCount(ClientPlayerEntity player, List<String> matchingItemIds) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		int total = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && ids.contains(itemId(stack))) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static boolean selectRequiredTool(MinecraftClient client, ClientPlayerEntity player, List<String> requiredToolItemIds) {
		if (requiredToolItemIds == null || requiredToolItemIds.isEmpty()) {
			return true;
		}
		Set<String> required = new HashSet<>(requiredToolItemIds);
		int inventorySlot = -1;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && required.contains(itemId(stack))) {
				inventorySlot = slot;
				break;
			}
		}
		if (inventorySlot < 0) {
			return false;
		}
		if (inventorySlot < 9) {
			selectAndSyncHotbarSlot(client, player, inventorySlot);
			return true;
		}
		int sourceSlot = PlayerScreenHandler.INVENTORY_START + (inventorySlot - 9);
		int hotbarSlot = player.getInventory().getSelectedSlot();
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, sourceSlot, hotbarSlot, SlotActionType.SWAP, player);
		selectAndSyncHotbarSlot(client, player, hotbarSlot);
		return true;
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		if (player.getInventory().getSelectedSlot() == hotbarSlot) {
			return;
		}
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		releaseControls();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		releaseControls();
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private void clearBreak(MinecraftClient client) {
		if (breakingTarget != null && client != null && client.interactionManager != null) {
			client.interactionManager.cancelBlockBreaking();
		}
		breakingTarget = null;
		breakStartedTick = -1L;
	}

	private void cancelNavigation() {
		if (navigationStarted && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		navigationStarted = false;
	}

	private void releaseControls() {
		MinecraftClient client = clientSupplier.get();
		cancelNavigation();
		clearBreak(client);
		movement.stop(client);
	}

	private void reset() {
		releaseControls();
		appliedTask = null;
		snapshot = TaskExecutionSnapshot.idle();
		terminalEventEmitted = false;
		searchOrigin = null;
		batch = List.of();
		batchCursor = 0;
		pickupTicksRemaining = -1;
		unreachableDropIds.clear();
		inventoryBeforeBreak = 0;
		surfacing = false;
		harvestedBlocks = 0;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		return left != null && right != null
			&& left.type() == WorldTaskType.BOUNDED_HARVEST
			&& right.type() == WorldTaskType.BOUNDED_HARVEST
			&& Objects.equals(left.taskId(), right.taskId());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "BoundedBlockHarvest", event, null, null);
	}

	private static GoalPosition goalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
	}

	private record HarvestTarget(BlockPos pos, String blockId, BoundedHarvestPolicy.SourceEnvironment environment) {
	}
}
