package ai.moeru.airicraft.agent.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class MovementController {
	private static final long STUCK_TICKS = 20L;
	private static final double STUCK_DISTANCE_EPSILON = 0.15D;

	private boolean movingForward;
	private boolean sprinting;
	private boolean jumping;
	private boolean descending;
	private boolean stuck;
	private long movingSinceTick = -1L;
	private Vec3d movementStartPos;
	private Boolean previousAutoJumpValue;

	public void moveForward(MinecraftClient client, boolean sprint, boolean jump, long tick) {
		if (client == null) {
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null) {
			stop(client);
			return;
		}

		if (!movingForward || movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		movingForward = true;
		sprinting = sprint;
		boolean effectiveJump = shouldJump(player, jump);
		jumping = effectiveJump;
		descending = false;
		enableAutoJump(client);

		client.options.forwardKey.setPressed(true);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.sprintKey.setPressed(sprint);
		client.options.jumpKey.setPressed(effectiveJump);
		client.options.sneakKey.setPressed(false);
		player.setSprinting(sprint);
		player.setSneaking(false);

		updateStuckState(player, tick);
	}

	public void swimUp(MinecraftClient client, boolean forward, boolean sprint, long tick) {
		swimUp(client, forward, sprint, false, false, false, tick);
	}

	public void swimUp(MinecraftClient client, boolean forward, boolean sprint, boolean left, boolean right, boolean back, long tick) {
		moveDirectional(client, forward, back, left, right, sprint, true, tick);
	}

	public void moveDirectional(
		MinecraftClient client,
		boolean forward,
		boolean back,
		boolean left,
		boolean right,
		boolean sprint,
		boolean jump,
		long tick
	) {
		moveDirectional(client, forward, back, left, right, sprint, jump, false, tick);
	}

	public void moveDirectional(
		MinecraftClient client,
		boolean forward,
		boolean back,
		boolean left,
		boolean right,
		boolean sprint,
		boolean jump,
		boolean descend,
		long tick
	) {
		if (client == null) {
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null) {
			stop(client);
			return;
		}

		if (movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		boolean effectiveForward = forward && !back;
		boolean effectiveSprint = effectiveForward && sprint;
		movingForward = effectiveForward;
		sprinting = effectiveSprint;
		jumping = jump;
		descending = descend && !jump;
		enableAutoJump(client);

		client.options.forwardKey.setPressed(effectiveForward);
		client.options.backKey.setPressed(back);
		client.options.leftKey.setPressed(left && !right);
		client.options.rightKey.setPressed(right && !left);
		client.options.sprintKey.setPressed(effectiveSprint);
		client.options.jumpKey.setPressed(jump);
		client.options.sneakKey.setPressed(descending);
		player.setSprinting(effectiveSprint);
		player.setSneaking(descending);

		updateStuckState(player, tick);
	}

	public void stop(MinecraftClient client) {
		if (!isControllingMovement()) {
			return;
		}

		movingForward = false;
		sprinting = false;
		jumping = false;
		descending = false;
		stuck = false;
		movingSinceTick = -1L;
		movementStartPos = null;

		if (client == null) {
			return;
		}

		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
		restoreAutoJump(client);
		if (client.player != null) {
			client.player.setSprinting(false);
			client.player.setSneaking(false);
		}
	}

	public MovementStateSnapshot snapshot() {
		return new MovementStateSnapshot(movingForward, sprinting, jumping, stuck, movingSinceTick);
	}

	private void updateStuckState(ClientPlayerEntity player, long tick) {
		if (movementStartPos == null || movingSinceTick < 0L) {
			stuck = false;
			return;
		}
		if (tick - movingSinceTick < STUCK_TICKS) {
			stuck = false;
			return;
		}

		Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
		double movedDistance = currentPos.distanceTo(movementStartPos);
		stuck = movedDistance < STUCK_DISTANCE_EPSILON;
		if (!stuck) {
			movingSinceTick = tick;
			movementStartPos = currentPos;
		}
	}

	private static boolean shouldJump(ClientPlayerEntity player, boolean requestedJump) {
		if (requestedJump) {
			return true;
		}

		if (player.isTouchingWater() || player.isSubmergedInWater()) {
			return true;
		}

		return player.horizontalCollision && player.isOnGround();
	}

	private boolean isControllingMovement() {
		return movingForward
			|| sprinting
			|| jumping
			|| descending
			|| movingSinceTick >= 0L
			|| movementStartPos != null
			|| previousAutoJumpValue != null;
	}

	private void enableAutoJump(MinecraftClient client) {
		if (client == null || client.options == null) {
			return;
		}
		if (previousAutoJumpValue == null) {
			previousAutoJumpValue = client.options.getAutoJump().getValue();
		}
		client.options.getAutoJump().setValue(true);
	}

	private void restoreAutoJump(MinecraftClient client) {
		if (client == null || client.options == null || previousAutoJumpValue == null) {
			return;
		}
		client.options.getAutoJump().setValue(previousAutoJumpValue);
		previousAutoJumpValue = null;
	}
}
