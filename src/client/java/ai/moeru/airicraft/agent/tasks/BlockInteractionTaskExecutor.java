package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class BlockInteractionTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final int MIN_DIRECT_WATER_HORIZONTAL_SUPPORTS = 3;
	private static final int INTERACTION_NAVIGATION_RADIUS_BLOCKS = 3;
	private static final long INTERACTION_NAVIGATION_TIMEOUT_TICKS = 160L;
	private static final double DIRECT_INTERACTION_APPROACH_RANGE_SQUARED = 100.0D;
	private static final long PLACEMENT_CONFIRMATION_TIMEOUT_TICKS = 20L;
	private static final List<Direction> DEFAULT_SUPPORT_ORDER = List.of(
		Direction.DOWN,
		Direction.NORTH,
		Direction.SOUTH,
		Direction.WEST,
		Direction.EAST,
		Direction.UP
	);

	private final Supplier<MinecraftClient> clientSupplier;
	private final CameraController cameraController;
	private final MovementController movementController = new MovementController();
	private final int targetDelayTicks;
	private final BaritoneFacade baritoneFacade;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private int targetIndex;
	private int completedTargets;
	private long nextInteractionTick;
	private boolean navigationStarted;
	private int navigationTargetIndex = -1;
	private BlockPos navigationTarget;
	private GoalPosition navigationGoal;
	private long navigationStartTick;
	private PendingPlacementConfirmation pendingPlacementConfirmation;

	public BlockInteractionTaskExecutor() {
		this(0);
	}

	public BlockInteractionTaskExecutor(int targetDelayTicks) {
		this(MinecraftClient::getInstance, new CameraController(), targetDelayTicks, null);
	}

	public BlockInteractionTaskExecutor(int targetDelayTicks, BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, new CameraController(), targetDelayTicks, baritoneFacade);
	}

	public BlockInteractionTaskExecutor(int targetDelayTicks, CameraController cameraController) {
		this(MinecraftClient::getInstance, cameraController, targetDelayTicks, null);
	}

	public BlockInteractionTaskExecutor(int targetDelayTicks, CameraController cameraController, BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, cameraController, targetDelayTicks, baritoneFacade);
	}

	BlockInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this(clientSupplier, new CameraController(), 0, null);
	}

	BlockInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier, CameraController cameraController) {
		this(clientSupplier, cameraController, 0, null);
	}

	BlockInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier, CameraController cameraController, int targetDelayTicks) {
		this(clientSupplier, cameraController, targetDelayTicks, null);
	}

	BlockInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier, CameraController cameraController, int targetDelayTicks, BaritoneFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.targetDelayTicks = Math.max(0, targetDelayTicks);
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isBlockInteraction(activeTask.get().type())) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (!actuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || client.world == null || player == null) {
			return fail(request, "world_unavailable");
		}
		InteractionBusyDisposition busyDisposition = interactionBusyDisposition(
			player.currentScreenHandler != player.playerScreenHandler,
			player.currentScreenHandler == null || player.currentScreenHandler.getCursorStack().isEmpty()
		);
		if (busyDisposition == InteractionBusyDisposition.CLOSE_OPEN_SCREEN) {
			player.closeHandledScreen();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "closing_open_screen");
			return Optional.empty();
		}
		if (busyDisposition == InteractionBusyDisposition.FAIL) {
			return fail(request, "interaction_busy");
		}
		long tick = sessionSnapshot == null ? 0L : sessionSnapshot.tickCount();
		if (targetIndex > 0 && tick < nextInteractionTick) {
			snapshot = snapshot(
				TaskExecutionState.RUNNING,
				request,
				"waiting_between_targets targetIndex=" + targetIndex + " remainingTicks=" + (nextInteractionTick - tick)
			);
			return Optional.empty();
		}
		if (pendingPlacementConfirmation != null) {
			return confirmPendingPlacement(tick, client, request);
		}
		return request.type() == WorldTaskType.PLACE_BLOCK
			? placeBlock(tick, client, player, request, request.blockPlacement().targets().get(targetIndex))
			: useBlock(tick, client, player, request, request.blockUse().targets().get(targetIndex));
	}

	private Optional<TaskTerminalEvent> placeBlock(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockPlacementStepArgs.Target args
	) {
		BlockPos target = blockPos(args.targetPosition());
		if (!client.world.isChunkLoaded(target)) {
			return fail(request, targetFailure(target, "target_unloaded"));
		}
		BlockState before = client.world.getBlockState(target);
		if (!targetMaterial(args.requiredTargetMaterial(), "air_or_replaceable").matches(before)) {
			return fail(request, targetFailure(target, "target_material_mismatch beforeBlockId=" + blockId(before)));
		}
		Hand hand = resolveInteractionHand(client, player, request.blockPlacement().itemId());
		if (hand == null) {
			return fail(request, targetFailure(target, "required_item_missing itemId=" + request.blockPlacement().itemId()));
		}
		Optional<HitTarget> hitTarget = resolvePlacementHit(client, player, target, args.facePreference());
		if (hitTarget.isEmpty()) {
			return fail(request, targetFailure(target, "support_not_found"));
		}
		return interact(tick, client, player, request, hand, target, before, hitTarget.get());
	}

	private Optional<TaskTerminalEvent> useBlock(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockUseStepArgs.Target args
	) {
		BlockPos target = blockPos(args.targetPosition());
		if (!client.world.isChunkLoaded(target)) {
			return fail(request, targetFailure(target, "target_unloaded"));
		}
		BlockState before = client.world.getBlockState(target);
		TargetMaterial expectedTargetMaterial = targetMaterial(args.expectedTargetMaterial(), null);
		if (expectedTargetMaterial != null && !expectedTargetMaterial.matches(before)) {
			return fail(request, targetFailure(target, "target_material_mismatch beforeBlockId=" + blockId(before)));
		}
		Hand hand = resolveInteractionHand(client, player, request.blockUse().itemId());
		if (hand == null) {
			return fail(request, targetFailure(target, "required_item_missing itemId=" + request.blockUse().itemId()));
		}
		UseBlockInteractionMode mode = useBlockInteractionMode(!before.getFluidState().isEmpty(), before.isAir() || before.isReplaceable());
		if (mode == UseBlockInteractionMode.FLUID_ITEM_USE) {
			return useItemOnFluidTarget(tick, client, player, request, hand, target, before);
		}
		if (isHeldItem(player, hand, Items.WATER_BUCKET)
			&& isDirectWaterPlacementTarget(blockId(before), before.isAir() || before.isReplaceable())) {
			return useWaterBucketDirectly(tick, client, player, request, hand, target, before);
		}
		Optional<HitTarget> hitTarget = mode == UseBlockInteractionMode.SUPPORT_INTERACTION
			? resolvePlacementHit(client, player, target, args.facePreference())
			: Optional.of(hitOnBlock(target, before, facePreference(args.facePreference()).orElse(Direction.UP)));
		if (hitTarget.isEmpty()) {
			return fail(request, targetFailure(target, "support_not_found"));
		}
		if (!args.expectedSupportBlockIds().isEmpty() && !args.expectedSupportBlockIds().contains(blockId(hitTarget.get().supportState()))) {
			return fail(request, targetFailure(target, "support_block_mismatch supportPos=" + compactPos(hitTarget.get().supportPos()) + " supportBlockId=" + blockId(hitTarget.get().supportState())));
		}
		return interact(tick, client, player, request, hand, target, before, hitTarget.get());
	}

	private Optional<TaskTerminalEvent> interact(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Hand hand,
		BlockPos target,
		BlockState before,
		HitTarget hitTarget
	) {
		if (!withinInteractionRange(player, hitTarget.hitVec())) {
			return navigateTowardInteractionRange(tick, client, player, request, target, hitTarget, "target_out_of_range supportPos=" + compactPos(hitTarget.supportPos()));
		}
		movementController.stop(client);
		cameraController.lookAtNow(client, hitTarget.hitVec());
		boolean raycastMatchesHitTarget = raycastMatchesHitTarget(client, player, hitTarget);
		boolean shouldNavigateForMissingRaycast = shouldNavigateForMissingRaycast(request, player, hand, before, target, hitTarget, raycastMatchesHitTarget);
		if (shouldNavigateForMissingRaycast) {
			return navigateTowardInteractionRange(tick, client, player, request, target, hitTarget, "target_not_visible supportPos=" + compactPos(hitTarget.supportPos()));
		}
		clearNavigation();
		ActionResult blockResult = client.interactionManager.interactBlock(player, hand, hitTarget.hitResult());
		ActionResult itemResult = null;
		if (!blockResult.isAccepted() && request.type() == WorldTaskType.USE_BLOCK && !(blockResult instanceof ActionResult.Fail)) {
			cameraController.lookAtNow(client, hitTarget.hitVec());
			raycastMatchesHitTarget = raycastMatchesHitTarget(client, player, hitTarget);
			if (raycastMatchesHitTarget) {
				itemResult = client.interactionManager.interactItem(player, hand);
			}
		}
		if (!blockResult.isAccepted() && (itemResult == null || !itemResult.isAccepted())) {
			if (shouldNavigateForMissingRaycast) {
				return navigateTowardInteractionRange(tick, client, player, request, target, hitTarget, "target_not_visible supportPos=" + compactPos(hitTarget.supportPos()));
			}
			return fail(request, targetFailure(target, "interaction_failed blockInteractionResult=" + blockResult
				+ " itemInteractionResult=" + (itemResult == null ? "not_attempted" : itemResult)
				+ " itemRaycastMatches=" + raycastMatchesHitTarget
				+ " supportPos=" + compactPos(hitTarget.supportPos())
				+ " face=" + hitTarget.face().asString()
				+ " beforeBlockId=" + blockId(before)
				+ " itemId=" + itemId(hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack())));
		}
		player.swingHand(hand);
		BlockState after = client.world.isChunkLoaded(target) ? client.world.getBlockState(target) : before;
		String message = "block_interaction_succeeded"
			+ " type=" + request.type().name()
			+ " targetIndex=" + targetIndex
			+ " targetCount=" + targetCount(request)
			+ " targetPos=" + compactPos(target)
			+ " itemId=" + itemId(hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack())
			+ " supportPos=" + compactPos(hitTarget.supportPos())
			+ " face=" + hitTarget.face().asString()
			+ " blockInteractionResult=" + blockResult
			+ " itemInteractionResult=" + (itemResult == null ? "not_attempted" : itemResult)
			+ " beforeBlockId=" + blockId(before)
			+ " afterBlockId=" + blockId(after);
		if (request.type() == WorldTaskType.PLACE_BLOCK && !placementConfirmed(after)) {
			pendingPlacementConfirmation = new PendingPlacementConfirmation(target, tick, message);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_place_block_confirmation"
				+ " targetIndex=" + targetIndex
				+ " targetPos=" + compactPos(target)
				+ " beforeBlockId=" + blockId(before)
				+ " afterBlockId=" + blockId(after));
			return Optional.empty();
		}
		return completeTarget(tick, request, message);
	}

	private Optional<TaskTerminalEvent> confirmPendingPlacement(
		long tick,
		MinecraftClient client,
		WorldTaskRequest request
	) {
		PendingPlacementConfirmation pending = pendingPlacementConfirmation;
		if (pending == null) {
			return Optional.empty();
		}
		if (!client.world.isChunkLoaded(pending.target())) {
			return fail(request, targetFailure(pending.target(), "target_unloaded_during_confirmation"));
		}
		BlockState current = client.world.getBlockState(pending.target());
		if (placementConfirmed(current)) {
			pendingPlacementConfirmation = null;
			return completeTarget(tick, request, pending.successMessage()
				+ " confirmedBlockId=" + blockId(current)
				+ " confirmationTicks=" + (tick - pending.startedTick()));
		}
		if (tick - pending.startedTick() > PLACEMENT_CONFIRMATION_TIMEOUT_TICKS) {
			pendingPlacementConfirmation = null;
			return fail(request, targetFailure(pending.target(), "placement_not_confirmed"
				+ " afterBlockId=" + blockId(current)
				+ " confirmationTimeoutTicks=" + (tick - pending.startedTick())));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_place_block_confirmation"
			+ " targetIndex=" + targetIndex
			+ " targetPos=" + compactPos(pending.target())
			+ " afterBlockId=" + blockId(current)
			+ " elapsedTicks=" + (tick - pending.startedTick()));
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> useItemOnFluidTarget(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Hand hand,
		BlockPos target,
		BlockState before
	) {
		if (!withinInteractionRange(player, Vec3d.ofCenter(target))) {
			return navigateTowardTargetRange(tick, client, player, request, target, "target_out_of_range");
		}
		movementController.stop(client);
		clearNavigation();
		Vec3d hitVec = Vec3d.ofCenter(target);
		cameraController.lookAtNow(client, hitVec);
		String beforeItemId = itemId(hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack());
		boolean itemFluidRaycastMatches = raycastMatchesTarget(
			client,
			player,
			target,
			hitVec,
			RaycastContext.FluidHandling.ANY
		);
		ActionResult itemResult = client.interactionManager.interactItem(player, hand);
		if (!itemResult.isAccepted()) {
			return fail(request, targetFailure(target, "fluid_item_interaction_failed"
				+ " itemInteractionResult=" + itemResult
				+ " itemFluidRaycastMatches=" + itemFluidRaycastMatches
				+ " beforeBlockId=" + blockId(before)
				+ " itemId=" + beforeItemId));
		}
		player.swingHand(hand);
		BlockState after = client.world.isChunkLoaded(target) ? client.world.getBlockState(target) : before;
		String afterItemId = itemId(hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack());
		return completeTarget(tick, request, "block_interaction_succeeded"
			+ " type=" + request.type().name()
			+ " targetIndex=" + targetIndex
			+ " targetCount=" + targetCount(request)
			+ " targetPos=" + compactPos(target)
			+ " itemId=" + beforeItemId
			+ " afterItemId=" + afterItemId
			+ " directFluidItemUse=true"
			+ " itemFluidRaycastMatches=" + itemFluidRaycastMatches
			+ " supportPos=direct"
			+ " face=direct"
			+ " itemInteractionResult=" + itemResult
			+ " beforeBlockId=" + blockId(before)
			+ " afterBlockId=" + blockId(after));
	}

	private Optional<TaskTerminalEvent> useWaterBucketDirectly(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Hand hand,
		BlockPos target,
		BlockState before
	) {
		if (!withinInteractionRange(player, Vec3d.ofCenter(target))) {
			return navigateTowardTargetRange(tick, client, player, request, target, "target_out_of_range");
		}
		movementController.stop(client);
		clearNavigation();
		String beforeBlockId = blockId(before);
		if (!isDirectWaterPlacementTarget(beforeBlockId, before.isAir() || before.isReplaceable())) {
			return fail(request, targetFailure(target, "fluid_target_not_replaceable beforeBlockId=" + blockId(before)));
		}
		int horizontalSolidNeighbors = horizontalSolidNeighborCount(client.world, target);
		if (!isSafeDirectWaterTarget(horizontalSolidNeighbors)) {
			return fail(request, targetFailure(target, "unsafe_fluid_target"
				+ " horizontalSolidNeighbors=" + horizontalSolidNeighbors
				+ " beforeBlockId=" + blockId(before)));
		}
		cameraController.lookAtNow(client, Vec3d.ofCenter(target));
		Optional<String> directPlacement = placeWaterDirectly(client, player, hand, target);
		if (directPlacement.isPresent()) {
			BlockState after = client.world.isChunkLoaded(target) ? client.world.getBlockState(target) : before;
			player.swingHand(hand);
			return completeTarget(tick, request, "block_interaction_succeeded"
				+ " type=" + request.type().name()
				+ " targetIndex=" + targetIndex
				+ " targetCount=" + targetCount(request)
				+ " targetPos=" + compactPos(target)
				+ " itemId=minecraft:water_bucket"
				+ " directFluidPlacement=true"
				+ " supportPos=direct"
				+ " face=direct"
				+ " beforeBlockId=" + beforeBlockId
				+ " afterBlockId=" + blockId(after)
				+ " message=" + directPlacement.get());
		}
		return fail(request, targetFailure(target, "direct_fluid_placement_unavailable"));
	}

	private Optional<TaskTerminalEvent> navigateTowardInteractionRange(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockPos target,
		HitTarget hitTarget,
		String outOfRangeReason
	) {
		if (startOrContinueDirectApproach(tick, client, player, request, target, hitTarget.hitVec(), outOfRangeReason)) {
			return Optional.empty();
		}
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return fail(request, targetFailure(target, outOfRangeReason));
		}
		Optional<GoalPosition> standGoal = interactionStandPosition(client, player, target, hitTarget);
		if (!navigationStarted || navigationTargetIndex != targetIndex || !target.equals(navigationTarget)) {
			if (standGoal.isPresent()) {
				navigationGoal = standGoal.get();
				baritoneFacade.startNavigate(navigationGoal);
			}
			else {
				navigationGoal = new GoalPosition(target.getX(), target.getY(), target.getZ(), false);
				baritoneFacade.startNavigateNear(navigationGoal, INTERACTION_NAVIGATION_RADIUS_BLOCKS);
			}
			navigationStarted = true;
			navigationTargetIndex = targetIndex;
			navigationTarget = target;
			navigationStartTick = tick;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigation_started targetIndex=" + targetIndex + " targetPos=" + compactPos(target)
				+ " navigationGoal=" + compactGoal(navigationGoal)
				+ " navigationMode=" + (standGoal.isPresent() ? "stand_position" : "near_target"));
			return Optional.empty();
		}
		Optional<String> pathEvent = baritoneFacade.pollPathEvent();
		if (navigationGoalReached(pathEvent)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigation_goal_reached targetIndex=" + targetIndex
				+ " targetPos=" + compactPos(target)
				+ " navigationGoal=" + compactGoal(navigationGoal)
				+ " navigationEvent=" + pathEvent.orElse("reached"));
			return Optional.empty();
		}
		BlockInteractionNavigationOutcome outcome = blockInteractionNavigationOutcome(pathEvent, tick - navigationStartTick);
		if (shouldFallbackToDirectApproachAfterNavigationFailure(pathEvent, player.squaredDistanceTo(hitTarget.hitVec()), movementController.snapshot().stuck())
			&& startOrContinueDirectApproach(tick, client, player, request, target, hitTarget.hitVec(), outOfRangeReason + " navigationEvent=" + pathEvent.orElse(""))) {
			return Optional.empty();
		}
		if (outcome == BlockInteractionNavigationOutcome.FAILED) {
			String suffix = pathEvent.map(event -> " navigationEvent=" + event).orElse(" navigationTimeoutTicks=" + (tick - navigationStartTick));
			return fail(request, targetFailure(target, outOfRangeReason + suffix + " navigationGoal=" + compactGoal(navigationGoal)));
		}
		if (outcome == BlockInteractionNavigationOutcome.AT_GOAL_BUT_STILL_OUT_OF_RANGE) {
			return fail(request, targetFailure(target, outOfRangeReason + " navigationEvent=" + pathEvent.orElse("AT_GOAL") + " navigationGoal=" + compactGoal(navigationGoal)));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigating targetIndex=" + targetIndex
			+ " targetPos=" + compactPos(target)
			+ " navigationGoal=" + compactGoal(navigationGoal));
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> navigateTowardTargetRange(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockPos target,
		String outOfRangeReason
	) {
		if (startOrContinueDirectApproach(tick, client, player, request, target, Vec3d.ofCenter(target), outOfRangeReason)) {
			return Optional.empty();
		}
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return fail(request, targetFailure(target, outOfRangeReason));
		}
		if (!navigationStarted || navigationTargetIndex != targetIndex || !target.equals(navigationTarget)) {
			navigationGoal = new GoalPosition(target.getX(), target.getY(), target.getZ(), false);
			baritoneFacade.startNavigateNear(navigationGoal, INTERACTION_NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
			navigationTargetIndex = targetIndex;
			navigationTarget = target;
			navigationStartTick = tick;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigation_started targetIndex=" + targetIndex
				+ " targetPos=" + compactPos(target)
				+ " navigationGoal=" + compactGoal(navigationGoal)
				+ " navigationMode=near_target");
			return Optional.empty();
		}
		Optional<String> pathEvent = baritoneFacade.pollPathEvent();
		if (navigationGoalReached(pathEvent)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigation_goal_reached targetIndex=" + targetIndex
				+ " targetPos=" + compactPos(target)
				+ " navigationGoal=" + compactGoal(navigationGoal)
				+ " navigationEvent=" + pathEvent.orElse("reached"));
			return Optional.empty();
		}
		BlockInteractionNavigationOutcome outcome = blockInteractionNavigationOutcome(pathEvent, tick - navigationStartTick);
		if (shouldFallbackToDirectApproachAfterNavigationFailure(pathEvent, player.squaredDistanceTo(Vec3d.ofCenter(target)), movementController.snapshot().stuck())
			&& startOrContinueDirectApproach(tick, client, player, request, target, Vec3d.ofCenter(target), outOfRangeReason + " navigationEvent=" + pathEvent.orElse(""))) {
			return Optional.empty();
		}
		if (outcome == BlockInteractionNavigationOutcome.FAILED) {
			String suffix = pathEvent.map(event -> " navigationEvent=" + event).orElse(" navigationTimeoutTicks=" + (tick - navigationStartTick));
			return fail(request, targetFailure(target, outOfRangeReason + suffix + " navigationGoal=" + compactGoal(navigationGoal)));
		}
		if (outcome == BlockInteractionNavigationOutcome.AT_GOAL_BUT_STILL_OUT_OF_RANGE) {
			return fail(request, targetFailure(target, outOfRangeReason + " navigationEvent=" + pathEvent.orElse("AT_GOAL") + " navigationGoal=" + compactGoal(navigationGoal)));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_navigating targetIndex=" + targetIndex
			+ " targetPos=" + compactPos(target)
			+ " navigationGoal=" + compactGoal(navigationGoal));
		return Optional.empty();
	}

	private boolean startOrContinueDirectApproach(
		long tick,
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		BlockPos target,
		Vec3d aimPoint,
		String outOfRangeReason
	) {
		double squaredDistance = player == null || aimPoint == null ? Double.MAX_VALUE : player.squaredDistanceTo(aimPoint);
		if (!allowsDirectInteractionApproach(outOfRangeReason) || !shouldUseDirectInteractionApproach(squaredDistance, movementController.snapshot().stuck())) {
			if (movementController.snapshot().stuck()) {
				movementController.stop(client);
			}
			return false;
		}
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
		navigationTargetIndex = -1;
		navigationTarget = null;
		navigationGoal = null;
		navigationStartTick = 0L;
		cameraController.lookAtNow(client, aimPoint);
		movementController.moveForward(client, true, false, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_direct_approach targetIndex=" + targetIndex
			+ " targetPos=" + compactPos(target)
			+ " reason=" + outOfRangeReason);
		return true;
	}

	static boolean shouldUseDirectInteractionApproach(double squaredDistance, boolean movementStuck) {
		return !movementStuck && squaredDistance <= DIRECT_INTERACTION_APPROACH_RANGE_SQUARED;
	}

	static boolean allowsDirectInteractionApproach(String reason) {
		return reason == null || !reason.contains("target_not_visible");
	}

	static boolean shouldFallbackToDirectApproachAfterNavigationFailure(Optional<String> pathEvent, double squaredDistance, boolean movementStuck) {
		return pathEvent
			.map(event -> {
				String normalized = event.trim().toUpperCase(Locale.ROOT);
				return "CANCELED".equals(normalized) || "CANCELLED".equals(normalized);
			})
			.orElse(false)
			&& shouldUseDirectInteractionApproach(squaredDistance, movementStuck);
	}

	private boolean navigationGoalReached(Optional<String> pathEvent) {
		if (baritoneFacade == null || navigationGoal == null || pathEvent.isEmpty()) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!"AT_GOAL".equals(normalized) && !"CANCELED".equals(normalized) && !"CANCELLED".equals(normalized)) {
			return false;
		}
		return baritoneFacade.navigationGoalReached(navigationGoal);
	}

	private static Optional<GoalPosition> interactionStandPosition(MinecraftClient client, ClientPlayerEntity player, BlockPos target, HitTarget hitTarget) {
		if (client == null || client.world == null || player == null || target == null || hitTarget == null) {
			return Optional.empty();
		}
		BlockPos current = player.getBlockPos();
		GoalPosition best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos candidate : interactionStandCandidates(target, hitTarget.supportPos())) {
			if (candidate.equals(current) || !isStandable(client, candidate)) {
				continue;
			}
			if (!withinInteractionRange(candidate, hitTarget.hitVec())) {
				continue;
			}
			double distance = current.getSquaredDistance(candidate);
			if (distance < bestDistance) {
				best = new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), true);
				bestDistance = distance;
			}
		}
		return Optional.ofNullable(best);
	}

	static List<BlockPos> interactionStandCandidates(BlockPos target, BlockPos support) {
		return List.of(
			target.north(),
			target.north().up(),
			target.south(),
			target.south().up(),
			target.west(),
			target.west().up(),
			target.east(),
			target.east().up(),
			support.north(),
			support.north().up(),
			support.south(),
			support.south().up(),
			support.west(),
			support.west().up(),
			support.east(),
			support.east().up()
		);
	}

	static BlockInteractionNavigationOutcome blockInteractionNavigationOutcome(Optional<String> pathEvent, long elapsedTicks) {
		if (pathEvent.isPresent()) {
			String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
			if ("AT_GOAL".equals(normalized)) {
				return BlockInteractionNavigationOutcome.AT_GOAL_BUT_STILL_OUT_OF_RANGE;
			}
			if ("CALC_FAILED".equals(normalized) || "CANCELED".equals(normalized) || "CANCELLED".equals(normalized)) {
				return BlockInteractionNavigationOutcome.FAILED;
			}
		}
		if (elapsedTicks > INTERACTION_NAVIGATION_TIMEOUT_TICKS) {
			return BlockInteractionNavigationOutcome.FAILED;
		}
		return BlockInteractionNavigationOutcome.WAIT;
	}

	private Optional<HitTarget> resolvePlacementHit(MinecraftClient client, ClientPlayerEntity player, BlockPos target, String facePreference) {
		List<Direction> directions = facePreference(facePreference)
			.map(List::of)
			.orElse(DEFAULT_SUPPORT_ORDER);
		HitTarget fallback = null;
		for (Direction direction : directions) {
			BlockPos support = target.offset(direction);
			if (!client.world.isChunkLoaded(support)) {
				continue;
			}
			BlockState supportState = client.world.getBlockState(support);
			Direction face = direction.getOpposite();
			if (supportState.isAir() || supportState.isReplaceable()) {
				continue;
			}
			HitTarget hitTarget = hitOnBlock(support, supportState, face);
			if (fallback == null) {
				fallback = hitTarget;
			}
			if (withinInteractionRange(player, hitTarget.hitVec()) && raycastMatchesHitTarget(client, player, hitTarget)) {
				return Optional.of(hitTarget);
			}
		}
		return Optional.ofNullable(fallback);
	}

	private static boolean raycastMatchesHitTarget(MinecraftClient client, ClientPlayerEntity player, HitTarget hitTarget) {
		if (client == null || client.world == null || player == null || hitTarget == null) {
			return false;
		}
		return raycastMatchesTarget(
			client,
			player,
			hitTarget.supportPos(),
			hitTarget.hitVec(),
			RaycastContext.FluidHandling.NONE
		);
	}

	private static boolean raycastMatchesTarget(
		MinecraftClient client,
		ClientPlayerEntity player,
		BlockPos target,
		Vec3d hitVec,
		RaycastContext.FluidHandling fluidHandling
	) {
		if (client == null || client.world == null || player == null || target == null || hitVec == null || fluidHandling == null) {
			return false;
		}
		BlockHitResult raycast = client.world.raycast(new RaycastContext(
			player.getEyePos(),
			hitVec,
			RaycastContext.ShapeType.COLLIDER,
			fluidHandling,
			player
		));
		return raycast.getType() == HitResult.Type.BLOCK
			&& raycast.getBlockPos().equals(target);
	}

	private static HitTarget hitOnBlock(BlockPos support, BlockState supportState, Direction face) {
		Vec3d center = Vec3d.ofCenter(support);
		Vec3d hitVec = center.add(
			face.getOffsetX() * 0.5D,
			face.getOffsetY() * 0.5D,
			face.getOffsetZ() * 0.5D
		);
		return new HitTarget(support, supportState, face, hitVec, new BlockHitResult(hitVec, face, support, false));
	}

	private static int horizontalSolidNeighborCount(World world, BlockPos target) {
		if (world == null) {
			return 0;
		}
		int count = 0;
		for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
			BlockPos neighbor = target.offset(direction);
			if (!world.isChunkLoaded(neighbor)) {
				continue;
			}
			BlockState state = world.getBlockState(neighbor);
			if (!state.isAir() && !state.isReplaceable()) {
				count++;
			}
		}
		return count;
	}

	static boolean isSafeDirectWaterTarget(int horizontalSolidNeighbors) {
		return horizontalSolidNeighbors >= MIN_DIRECT_WATER_HORIZONTAL_SUPPORTS;
	}

	static boolean isDirectWaterPlacementTarget(String blockId, boolean airOrReplaceable) {
		if (airOrReplaceable) {
			return true;
		}
		return switch (blockId) {
			case "minecraft:grass_block", "minecraft:dirt" -> true;
			default -> false;
		};
	}

	static UseBlockInteractionMode useBlockInteractionMode(boolean targetHasFluid, boolean targetAirOrReplaceable) {
		if (targetHasFluid) {
			return UseBlockInteractionMode.FLUID_ITEM_USE;
		}
		return targetAirOrReplaceable
			? UseBlockInteractionMode.SUPPORT_INTERACTION
			: UseBlockInteractionMode.BLOCK_INTERACTION;
	}

	static InteractionBusyDisposition interactionBusyDisposition(boolean containerOpen, boolean cursorEmpty) {
		if (!cursorEmpty) {
			return InteractionBusyDisposition.FAIL;
		}
		return containerOpen ? InteractionBusyDisposition.CLOSE_OPEN_SCREEN : InteractionBusyDisposition.READY;
	}

	static boolean requiresSupportRaycast(BlockState before, BlockPos target, HitTarget hitTarget) {
		return requiresSupportRaycast(before.isAir() || before.isReplaceable(), target.equals(hitTarget.supportPos()));
	}

	static boolean requiresSupportRaycast(boolean targetAirOrReplaceable, boolean supportIsTarget) {
		return targetAirOrReplaceable || !supportIsTarget;
	}

	static boolean shouldNavigateForMissingRaycast(
		WorldTaskRequest request,
		ClientPlayerEntity player,
		Hand hand,
		BlockState before,
		BlockPos target,
		HitTarget hitTarget,
		boolean raycastMatchesHitTarget
	) {
		if (raycastMatchesHitTarget) {
			return false;
		}
		if (request.type() == WorldTaskType.USE_BLOCK && isCropPlantingItem(heldStack(player, hand))) {
			return false;
		}
		if (requiresSupportRaycast(before, target, hitTarget)) {
			return true;
		}
		return request.type() == WorldTaskType.USE_BLOCK && isHeldItem(player, hand, Items.WATER_BUCKET);
	}

	static boolean isCropPlantingItem(ItemStack stack) {
		return isCropPlantingItemId(itemId(stack));
	}

	static boolean isCropPlantingItemId(String itemId) {
		return "minecraft:wheat_seeds".equals(itemId)
			|| "minecraft:beetroot_seeds".equals(itemId)
			|| "minecraft:melon_seeds".equals(itemId)
			|| "minecraft:pumpkin_seeds".equals(itemId)
			|| "minecraft:torchflower_seeds".equals(itemId)
			|| "minecraft:pitcher_pod".equals(itemId)
			|| "minecraft:carrot".equals(itemId)
			|| "minecraft:potato".equals(itemId)
			|| "minecraft:nether_wart".equals(itemId);
	}

	static boolean placementConfirmed(BlockState state) {
		return state != null && placementConfirmed(!(state.isAir() || state.isReplaceable()));
	}

	static boolean placementConfirmed(boolean targetSolid) {
		return targetSolid;
	}

	private static Optional<String> placeWaterDirectly(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockPos target) {
		if (client.getServer() == null || client.world == null) {
			return Optional.empty();
		}
		ServerWorld serverWorld = client.getServer().getWorld(client.world.getRegistryKey());
		if (serverWorld == null) {
			return Optional.empty();
		}
		ServerPlayerEntity serverPlayer = serverWorld.getServer().getPlayerManager().getPlayer(player.getUuid());
		if (serverPlayer == null) {
			return Optional.empty();
		}
		int serverBucketSlot = findServerInventoryItemSlot(serverPlayer, hand, Items.WATER_BUCKET);
		if (serverBucketSlot < 0) {
			return Optional.empty();
		}
		BlockState serverBefore = serverWorld.getBlockState(target);
		if (!isDirectWaterPlacementTarget(blockId(serverBefore), serverBefore.isAir() || serverBefore.isReplaceable())) {
			return Optional.empty();
		}
		boolean placed = serverWorld.setBlockState(target, Blocks.WATER.getDefaultState());
		if (!placed) {
			return Optional.empty();
		}
		ItemStack emptyBucket = new ItemStack(Items.BUCKET);
		replaceServerInventoryStack(serverPlayer, hand, serverBucketSlot, emptyBucket.copy());
		player.setStackInHand(hand, emptyBucket.copy());
		return Optional.of("server_world_set_block");
	}

	private static int findServerInventoryItemSlot(ServerPlayerEntity player, Hand hand, Item item) {
		if (hand == Hand.OFF_HAND && player.getOffHandStack().isOf(item)) {
			return PlayerInventory.OFF_HAND_SLOT;
		}
		PlayerInventory inventory = player.getInventory();
		int selectedSlot = inventory.getSelectedSlot();
		if (inventory.getSelectedStack().isOf(item)) {
			return selectedSlot;
		}
		for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
			if (inventory.getStack(slot).isOf(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static void replaceServerInventoryStack(ServerPlayerEntity player, Hand hand, int slot, ItemStack replacement) {
		if (hand == Hand.OFF_HAND && slot == PlayerInventory.OFF_HAND_SLOT) {
			player.setStackInHand(hand, replacement);
			return;
		}
		player.getInventory().setStack(slot, replacement);
	}

	private static boolean isHeldItem(ClientPlayerEntity player, Hand hand, Item item) {
		return heldStack(player, hand).isOf(item);
	}

	private static boolean isHeldItem(ServerPlayerEntity player, Hand hand, Item item) {
		return heldStack(player, hand).isOf(item);
	}

	private static ItemStack heldStack(ClientPlayerEntity player, Hand hand) {
		return hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack();
	}

	private static ItemStack heldStack(ServerPlayerEntity player, Hand hand) {
		return hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack();
	}

	private static Hand resolveInteractionHand(MinecraftClient client, ClientPlayerEntity player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return Hand.MAIN_HAND;
		}
		String offHandItemId = Registries.ITEM.getId(player.getOffHandStack().getItem()).toString();
		if (itemId.equals(offHandItemId) && !player.getOffHandStack().isEmpty()) {
			return Hand.OFF_HAND;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, itemId);
		if (sourceSlot < 0) {
			return null;
		}
		int hotbarIndex = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return Hand.MAIN_HAND;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarIndex, SlotActionType.SWAP, player);
		selectAndSyncHotbarSlot(client, player, hotbarIndex);
		ItemStack selected = player.getInventory().getSelectedStack();
		if (selected.isEmpty()) {
			return null;
		}
		String selectedItemId = Registries.ITEM.getId(selected.getItem()).toString();
		return itemId.equals(selectedItemId) ? Hand.MAIN_HAND : null;
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private static int findInventorySlot(ScreenHandler handler, String itemId) {
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> completeTarget(long tick, WorldTaskRequest request, String message) {
		completedTargets++;
		targetIndex++;
		if (targetIndex >= targetCount(request)) {
			return complete(request, "block_interactions_succeeded"
				+ " type=" + request.type().name()
				+ " completedTargets=" + completedTargets
				+ " lastResult=" + message);
		}
		nextInteractionTick = tick + targetDelayTicks;
		snapshot = snapshot(TaskExecutionState.RUNNING, request, message + " nextTargetDelayTicks=" + targetDelayTicks);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		clearNavigation();
		movementController.stop(clientSupplier.get());
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private static Optional<Direction> facePreference(String value) {
		if (value == null || value.isBlank() || "auto".equals(value)) {
			return Optional.empty();
		}
		return Optional.of(switch (value) {
			case "down" -> Direction.DOWN;
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			case "up" -> Direction.UP;
			default -> Direction.DOWN;
		});
	}

	private static TargetMaterial targetMaterial(String value, String fallback) {
		String normalized = value == null || value.isBlank() ? fallback : value;
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		return switch (normalized) {
			case "air" -> TargetMaterial.AIR;
			case "replaceable" -> TargetMaterial.REPLACEABLE;
			case "air_or_replaceable" -> TargetMaterial.AIR_OR_REPLACEABLE;
			default -> TargetMaterial.AIR_OR_REPLACEABLE;
		};
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, Vec3d pos) {
		return player.squaredDistanceTo(pos) <= INTERACTION_RANGE_SQUARED;
	}

	private static boolean withinInteractionRange(BlockPos standingPos, Vec3d pos) {
		return Vec3d.ofCenter(standingPos).squaredDistanceTo(pos) <= INTERACTION_RANGE_SQUARED;
	}

	private static boolean isStandable(MinecraftClient client, BlockPos pos) {
		if (client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.up())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(pos);
		BlockState head = client.world.getBlockState(pos.up());
		BlockState floor = client.world.getBlockState(pos.down());
		return (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& floor.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "none" : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static String compactGoal(GoalPosition position) {
		return position == null ? "none" : position.x() + "," + position.y() + "," + position.z();
	}

	private String targetFailure(BlockPos target, String reason) {
		return reason + " targetIndex=" + targetIndex + " targetPos=" + compactPos(target);
	}

	private static int targetCount(WorldTaskRequest request) {
		return request.type() == WorldTaskType.PLACE_BLOCK
			? request.blockPlacement().targets().size()
			: request.blockUse().targets().size();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.sourceJobId(), right.sourceJobId())
			&& left.type() == right.type();
	}

	private static boolean isBlockInteraction(WorldTaskType type) {
		return type == WorldTaskType.PLACE_BLOCK || type == WorldTaskType.USE_BLOCK;
	}

	static boolean actuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "BlockInteraction", event, null, null);
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

	private void reset() {
		clearNavigation();
		movementController.stop(clientSupplier.get());
		appliedTask = null;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
		targetIndex = 0;
		completedTargets = 0;
		nextInteractionTick = 0L;
		pendingPlacementConfirmation = null;
	}

	private void clearNavigation() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
		navigationTargetIndex = -1;
		navigationTarget = null;
		navigationGoal = null;
		navigationStartTick = 0L;
	}

	private enum TargetMaterial {
		AIR,
		REPLACEABLE,
		AIR_OR_REPLACEABLE;

		boolean matches(BlockState state) {
			return switch (this) {
				case AIR -> state.isAir();
				case REPLACEABLE -> !state.isAir() && state.isReplaceable();
				case AIR_OR_REPLACEABLE -> state.isAir() || state.isReplaceable();
			};
		}
	}

	enum UseBlockInteractionMode {
		FLUID_ITEM_USE,
		SUPPORT_INTERACTION,
		BLOCK_INTERACTION
	}

	enum BlockInteractionNavigationOutcome {
		WAIT,
		AT_GOAL_BUT_STILL_OUT_OF_RANGE,
		FAILED
	}

	enum InteractionBusyDisposition {
		READY,
		CLOSE_OPEN_SCREEN,
		FAIL
	}

	private record HitTarget(
		BlockPos supportPos,
		BlockState supportState,
		Direction face,
		Vec3d hitVec,
		BlockHitResult hitResult
	) {
	}

	private record PendingPlacementConfirmation(
		BlockPos target,
		long startedTick,
		String successMessage
	) {
	}
}
