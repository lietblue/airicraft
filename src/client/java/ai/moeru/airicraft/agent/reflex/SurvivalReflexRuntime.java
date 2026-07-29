package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.SurfaceMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SurvivalReflexRuntime {
	static final int BREATHABLE_STABLE_TICKS = 12;
	static final double THREAT_CLEAR_DISTANCE = 12.0D;
	static final double DEFEND_DISTANCE = 4.5D;
	static final double PROACTIVE_THREAT_DISTANCE = 8.0D;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;
	private static final int ESCAPE_PHASE_TICKS = 20;

	private final AgentConfig.ReflexConfig config;
	private final BaritoneFacade baritone;
	private final MovementController movementController;
	private final CameraController cameraController;
	private final Map<String, ObservedThreat> observedThreats = new LinkedHashMap<>();
	private final List<SurvivalReflexEvent> pendingEvents = new ArrayList<>();

	private SurvivalReflexSnapshot snapshot = SurvivalReflexSnapshot.idle();
	private boolean drowningDamageObserved;
	private long lastMobDamageTick = Long.MIN_VALUE;
	private int lastAir = Integer.MIN_VALUE;
	private int stuckTicks;
	private boolean safetyHoldActuating;
	private GoalPosition safeLandNavigationTarget;

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config) {
		this(config, new MovementController(), new CameraController(), null);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, BaritoneFacade baritone) {
		this(config, new MovementController(), new CameraController(), baritone);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController
	) {
		this(config, movementController, cameraController, null);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController,
		BaritoneFacade baritone
	) {
		this.config = Objects.requireNonNullElseGet(config, AgentConfig.ReflexConfig::defaults);
		this.movementController = Objects.requireNonNull(movementController, "movementController");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.baritone = baritone;
	}

	public SurvivalReflexSnapshot snapshot() {
		return snapshot;
	}

	public void observeDamage(DamageObservation observation) {
		if (observation == null) {
			return;
		}
		if (isDrowningDamage(observation.damageTypeId())) {
			drowningDamageObserved = true;
			return;
		}
		if (!observation.attackerLiving() || observation.attackerPlayer() || observation.attackerUuid() == null) {
			return;
		}
		observedThreats.put(observation.attackerUuid(), new ObservedThreat(
			observation.attackerUuid(),
			observation.attackerName(),
			observation.attackerEntityTypeId(),
			observation.tick()
		));
		lastMobDamageTick = observation.tick();
	}

	public SurvivalReflexSnapshot tick(
		MinecraftClient client,
		GoalPosition surfaceTarget,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators
	) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (!config.enabled() || client == null || client.world == null || player == null || player.isDead()) {
			reset(client);
			return snapshot;
		}

		boolean drowningDanger = drowningDanger(player, config.lowAirTicks(), drowningDamageObserved);
		detectProactiveThreats(client, player, tick);
		List<ResolvedThreat> threats = resolveThreats(client, player);
		boolean mobDanger = !threats.isEmpty() || recentlyDamagedByMob(tick, lastMobDamageTick, config.threatCooldownTicks());
		if (shouldBeginReflex(snapshot.state(), drowningDanger || mobDanger)) {
			SurvivalReflexCause cause = drowningDanger ? SurvivalReflexCause.DROWNING : SurvivalReflexCause.MOB_ATTACK;
			boolean hasInterruptedWork = interruptedWork != null && interruptedWork.hasInterruptedWork();
			SurvivalReflexAction action = drowningDanger
				? drowningAction(hasInterruptedWork)
				: chooseMobAction(healthRatio(player), threats);
			begin(cause, action, interruptedWork, player, threats, tick, releaseNormalActuators);
		}

		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			maintainDrowningSafetyHold(client, player, tick);
			refreshSnapshot(player, threats, snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure());
			return snapshot;
		}

		if (drowningDanger || snapshot.cause() == SurvivalReflexCause.DROWNING) {
			tickDrowning(client, player, surfaceTarget, drowningDanger, threats, tick);
		}
		else {
			tickMobAttack(client, player, threats, tick);
		}
		drowningDamageObserved = false;
		lastAir = player.getAir();
		return snapshot;
	}

	public ResumeResult resume(String holdId, long tick) {
		ResumeResult validation = validateResume(snapshot, holdId);
		if (validation != ResumeResult.RESUMED) {
			return validation;
		}
		releaseHold("resumed", tick);
		return ResumeResult.RESUMED;
	}

	public boolean releaseHold(String reason, long tick) {
		if (snapshot.state() != SurvivalReflexState.AWAITING_PLANNER) {
			return false;
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "released" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, snapshot.safetyEpoch(), null, null, null, List.of(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), -1L, tick, 0, null
		);
		observedThreats.clear();
		return true;
	}

	public boolean discardHold(String reason, long tick) {
		if (snapshot.holdId() == null) {
			return false;
		}
		if (snapshot.state() == SurvivalReflexState.AWAITING_PLANNER) {
			return releaseHold(reason, tick);
		}
		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			return false;
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "cancelled" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), null, null, null,
			snapshot.threats(), snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(),
			snapshot.startedTick(), snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
		return true;
	}

	public void reset(MinecraftClient client) {
		movementController.stop(client);
		observedThreats.clear();
		drowningDamageObserved = false;
		lastMobDamageTick = Long.MIN_VALUE;
		lastAir = Integer.MIN_VALUE;
		stuckTicks = 0;
		safetyHoldActuating = false;
		cancelSafeLandNavigation();
		long epoch = snapshot.safetyEpoch();
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, epoch, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
	}

	public List<SurvivalReflexEvent> drainEvents() {
		if (pendingEvents.isEmpty()) {
			return List.of();
		}
		List<SurvivalReflexEvent> events = List.copyOf(pendingEvents);
		pendingEvents.clear();
		return events;
	}

	private void begin(
		SurvivalReflexCause cause,
		SurvivalReflexAction action,
		InterruptedWork interruptedWork,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		Runnable releaseNormalActuators
	) {
		long nextEpoch = snapshot.safetyEpoch() + 1L;
		InterruptedWork work = interruptedWork == null ? InterruptedWork.none() : interruptedWork;
		String holdId = work.hasInterruptedWork() ? UUID.randomUUID().toString() : null;
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.ACTIVE, cause, action, nextEpoch, holdId, work.jobId(), work.actionExecutionId(),
			threatSnapshots(threats), player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(),
			tick, tick, 0, null
		);
		pendingEvents.add(new SurvivalReflexEvent("reflex.started", mapOfNullable(
			"safetyEpoch", nextEpoch,
			"holdId", holdId,
			"cause", cause.name(),
			"action", action.name(),
			"interruptedJobId", work.jobId(),
			"interruptedActionExecutionId", work.actionExecutionId(),
			"health", player.getHealth(),
			"air", player.getAir()
		)));
		try {
			if (releaseNormalActuators != null) {
				releaseNormalActuators.run();
			}
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("release_normal_actuators", exception, tick);
			snapshot = new SurvivalReflexSnapshot(
				snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
				snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
				snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
				snapshot.lastDangerTick(), snapshot.breathableTicks(), failureText(exception)
			);
		}
	}

	private void tickDrowning(
		MinecraftClient client,
		ClientPlayerEntity player,
		GoalPosition surfaceTarget,
		boolean danger,
		List<ResolvedThreat> threats,
		long tick
	) {
		boolean hasInterruptedWork = snapshot.holdId() != null;
		SurvivalReflexAction desiredAction = drowningAction(hasInterruptedWork);
		if (snapshot.cause() != SurvivalReflexCause.DROWNING || snapshot.action() != desiredAction) {
			changeAction(SurvivalReflexCause.DROWNING, desiredAction, tick);
		}
		boolean breathable = breathableAndRecovering(player, lastAir);
		boolean safeLand = player.isOnGround() && SurfaceMemory.isSurfaceStandingPosition(client, player.getBlockPos());
		boolean stable = stableDrowningRecovery(hasInterruptedWork, breathable, safeLand);
		int stableTicks = stable ? snapshot.breathableTicks() + 1 : 0;
		if (drowningResolved(stableTicks)) {
			if (!mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
				changeAction(SurvivalReflexCause.MOB_ATTACK, chooseMobAction(healthRatio(player), threats), tick);
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return;
			}
			resolve(client, player, threats, tick, hasInterruptedWork ? "breathing_restored" : "safe_land_reached");
			return;
		}

		try {
			if (stable) {
				cancelSafeLandNavigation();
				movementController.stop(client);
			}
			else if (shouldUseSafeLandNavigation(hasInterruptedWork)) {
				tickSafeLandNavigation(client, surfaceTarget, tick);
			}
			else {
				cancelSafeLandNavigation();
				movementController.swimUp(client, false, false, tick);
			}
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, null);
		}
		catch (RuntimeException exception) {
			String operation = hasInterruptedWork ? "swim_to_air" : "reach_safe_land";
			recordActuatorFailure(operation, exception, tick);
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, failureText(exception));
		}
	}

	private void tickSafeLandNavigation(
		MinecraftClient client,
		GoalPosition surfaceTarget,
		long tick
	) {
		if (!shouldNavigateToSafeLand(surfaceTarget != null, baritone != null && baritone.isLoaded())) {
			movementController.swimUp(client, false, false, tick);
			return;
		}
		if (!surfaceTarget.equals(safeLandNavigationTarget)) {
			movementController.stop(client);
			cancelSafeLandNavigation();
			baritone.startNavigate(surfaceTarget);
			safeLandNavigationTarget = surfaceTarget;
		}
		baritone.pollPathEvent().ifPresent(event -> {
			if ("CALC_FAILED".equalsIgnoreCase(event)
				|| "CANCELLED".equalsIgnoreCase(event)
				|| "CANCELED".equalsIgnoreCase(event)) {
				cancelSafeLandNavigation();
			}
		});
	}

	private void tickMobAttack(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
			resolve(client, player, threats, tick, "threats_clear");
			return;
		}
		SurvivalReflexAction nextAction = chooseMobAction(healthRatio(player), threats);
		if (snapshot.action() != nextAction || snapshot.cause() != SurvivalReflexCause.MOB_ATTACK) {
			changeAction(SurvivalReflexCause.MOB_ATTACK, nextAction, tick);
		}
		try {
			if (nextAction == SurvivalReflexAction.DEFEND && threats.size() == 1) {
				defend(client, player, threats.getFirst());
			}
			else if (!threats.isEmpty()) {
				flee(client, player, threats, tick);
			}
			else {
				movementController.stop(client);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure(nextAction.name().toLowerCase(java.util.Locale.ROOT), exception, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private void defend(MinecraftClient client, ClientPlayerEntity player, ResolvedThreat threat) {
		movementController.stop(client);
		cameraController.lookAtNow(client, threat.entity().getBoundingBox().getCenter());
		if (client.interactionManager != null && player.getAttackCooldownProgress(0.0F) >= ATTACK_READY_THRESHOLD) {
			client.interactionManager.attackEntity(player, threat.entity());
			player.swingHand(Hand.MAIN_HAND);
		}
	}

	private void flee(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		Vec3d playerPos = player.getPos();
		Vec3d away = Vec3d.ZERO;
		for (ResolvedThreat threat : threats) {
			Vec3d delta = playerPos.subtract(threat.entity().getPos());
			if (delta.lengthSquared() > 1.0E-6D) {
				away = away.add(delta.normalize());
			}
		}
		if (away.lengthSquared() <= 1.0E-6D) {
			away = player.getRotationVec(1.0F).multiply(-1.0D);
		}
		Vec3d horizontalAway = new Vec3d(away.x, 0.0D, away.z);
		if (horizontalAway.lengthSquared() > 1.0E-6D) {
			horizontalAway = horizontalAway.normalize();
			cameraController.lookAtNow(client, player.getEyePos().add(horizontalAway.multiply(8.0D)));
		}
		boolean stuck = movementController.snapshot().stuck();
		stuckTicks = stuck ? stuckTicks + 1 : 0;
		EscapeKeys keys = escapeKeys(stuck, stuckTicks);
		movementController.moveDirectional(
			client, keys.forward(), keys.back(), keys.left(), keys.right(), keys.sprint(), keys.jump(), tick
		);
	}

	private void resolve(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		String reason
	) {
		cancelSafeLandNavigation();
		movementController.stop(client);
		SurvivalReflexState nextState = snapshot.holdId() == null
			? SurvivalReflexState.IDLE
			: SurvivalReflexState.AWAITING_PLANNER;
		pendingEvents.add(new SurvivalReflexEvent("reflex.resolved", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"cause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"reason", reason,
			"nextState", nextState.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			nextState, snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), null
		);
		observedThreats.clear();
		lastMobDamageTick = Long.MIN_VALUE;
		stuckTicks = 0;
	}

	private void changeAction(SurvivalReflexCause cause, SurvivalReflexAction action, long tick) {
		pendingEvents.add(new SurvivalReflexEvent("reflex.action_changed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"previousCause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"previousAction", snapshot.action() == null ? null : snapshot.action().name(),
			"cause", cause.name(),
			"action", action.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), cause, action, snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
	}

	private void refreshSnapshot(
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long lastDangerTick,
		int breathableTicks,
		String actuatorFailure
	) {
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
			lastDangerTick, breathableTicks, actuatorFailure
		);
	}

	private void recordActuatorFailure(String operation, RuntimeException exception, long tick) {
		pendingEvents.add(new SurvivalReflexEvent("reflex.actuator_failed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"operation", operation,
			"message", failureText(exception),
			"tick", tick
		)));
	}

	static boolean drowningDanger(ClientPlayerEntity player, int lowAirTicks, boolean drowningDamageObserved) {
		return shouldStartDrowning(
			player != null && player.isSubmergedInWater(),
			player == null ? Integer.MAX_VALUE : player.getAir(),
			lowAirTicks,
			drowningDamageObserved
		);
	}

	static boolean breathableAndRecovering(ClientPlayerEntity player, int previousAir) {
		return player != null && breathableAndRecovering(
			player.isSubmergedInWater(), player.getAir(), player.getMaxAir(), previousAir
		);
	}

	static boolean shouldStartDrowning(boolean submerged, int air, int lowAirTicks, boolean drowningDamageObserved) {
		return drowningDamageObserved || (submerged && air <= Math.max(0, lowAirTicks));
	}

	static boolean breathableAndRecovering(boolean submerged, int air, int maxAir, int previousAir) {
		return !submerged && (air >= maxAir || previousAir == Integer.MIN_VALUE || air > previousAir);
	}

	static boolean drowningResolved(int breathableTicks) {
		return breathableTicks >= BREATHABLE_STABLE_TICKS;
	}

	static SurvivalReflexAction drowningAction(boolean hasInterruptedWork) {
		return hasInterruptedWork ? SurvivalReflexAction.SWIM_TO_AIR : SurvivalReflexAction.REACH_SAFE_LAND;
	}

	static boolean stableDrowningRecovery(boolean hasInterruptedWork, boolean breathable, boolean safeLand) {
		return hasInterruptedWork ? breathable : safeLand;
	}

	static boolean shouldNavigateToSafeLand(boolean targetAvailable, boolean baritoneLoaded) {
		return targetAvailable && baritoneLoaded;
	}

	static boolean shouldUseSafeLandNavigation(boolean hasInterruptedWork) {
		return !hasInterruptedWork;
	}

	static boolean shouldBeginReflex(SurvivalReflexState state, boolean dangerPresent) {
		return dangerPresent && state != SurvivalReflexState.ACTIVE;
	}

	static boolean shouldMaintainDrowningSafetyHold(
		SurvivalReflexState state,
		SurvivalReflexCause cause,
		boolean touchingWater
	) {
		return state == SurvivalReflexState.AWAITING_PLANNER
			&& cause == SurvivalReflexCause.DROWNING
			&& touchingWater;
	}

	static boolean mobThreatsResolved(int relevantThreatCount, long tick, long lastDamageTick, int cooldownTicks) {
		return relevantThreatCount == 0 && !recentlyDamagedByMob(tick, lastDamageTick, cooldownTicks);
	}

	static ResumeResult validateResume(SurvivalReflexSnapshot snapshot, String holdId) {
		SurvivalReflexSnapshot current = snapshot == null ? SurvivalReflexSnapshot.idle() : snapshot;
		if (current.state() == SurvivalReflexState.ACTIVE) {
			return ResumeResult.REFLEX_ACTIVE;
		}
		if (current.state() != SurvivalReflexState.AWAITING_PLANNER || current.holdId() == null) {
			return ResumeResult.NO_SAFETY_HOLD;
		}
		return current.holdId().equals(holdId) ? ResumeResult.RESUMED : ResumeResult.STALE_SAFETY_HOLD;
	}

	public static boolean shouldDefend(double healthRatio, int threatCount, double distance, boolean lineOfSight, double minHealthRatio) {
		return healthRatio > minHealthRatio && threatCount == 1 && distance <= DEFEND_DISTANCE && lineOfSight;
	}

	static boolean shouldDetectProactiveThreat(boolean hostile, boolean alive, double distance, boolean lineOfSight) {
		return hostile && alive && distance <= PROACTIVE_THREAT_DISTANCE && lineOfSight;
	}

	public static boolean recentlyDamagedByMob(long tick, long lastDamageTick, int cooldownTicks) {
		return lastDamageTick != Long.MIN_VALUE && tick - lastDamageTick < Math.max(0, cooldownTicks);
	}

	static EscapeKeys escapeKeys(boolean stuck, int stuckTicks) {
		if (!stuck) {
			return new EscapeKeys(true, false, false, false, true, true);
		}
		int phase = Math.floorDiv(Math.max(0, stuckTicks), ESCAPE_PHASE_TICKS) % 4;
		return switch (phase) {
			case 0 -> new EscapeKeys(true, false, true, false, true, true);
			case 1 -> new EscapeKeys(true, false, false, true, true, true);
			case 2 -> new EscapeKeys(false, true, true, false, false, true);
			default -> new EscapeKeys(false, true, false, true, false, true);
		};
	}

	private SurvivalReflexAction chooseMobAction(double healthRatio, List<ResolvedThreat> threats) {
		if (threats != null && threats.size() == 1) {
			ResolvedThreat threat = threats.getFirst();
			if (shouldDefend(healthRatio, 1, threat.distance(), threat.lineOfSight(), config.defendMinHealthRatio())) {
				return SurvivalReflexAction.DEFEND;
			}
		}
		return SurvivalReflexAction.FLEE;
	}

	private static boolean isDrowningDamage(String damageTypeId) {
		return damageTypeId != null && (damageTypeId.equals("drown") || damageTypeId.endsWith(":drown"));
	}

	private static double healthRatio(ClientPlayerEntity player) {
		return player == null || player.getMaxHealth() <= 0.0F ? 0.0D : player.getHealth() / player.getMaxHealth();
	}

	private void detectProactiveThreats(MinecraftClient client, ClientPlayerEntity player, long tick) {
		if (client == null || client.world == null || player == null) {
			return;
		}
		for (HostileEntity hostile : client.world.getEntitiesByClass(
			HostileEntity.class,
			player.getBoundingBox().expand(PROACTIVE_THREAT_DISTANCE),
			Entity::isAlive
		)) {
			double distance = player.distanceTo(hostile);
			boolean lineOfSight = player.canSee(hostile);
			if (!shouldDetectProactiveThreat(true, hostile.isAlive(), distance, lineOfSight)) {
				continue;
			}
			String uuid = hostile.getUuidAsString();
			if (observedThreats.containsKey(uuid)) {
				continue;
			}
			ObservedThreat observed = new ObservedThreat(
				uuid,
				hostile.getName().getString(),
				Registries.ENTITY_TYPE.getId(hostile.getType()).toString(),
				tick
			);
			observedThreats.put(uuid, observed);
			pendingEvents.add(new SurvivalReflexEvent("reflex.threat_detected", mapOfNullable(
				"source", "hostile_proximity",
				"uuid", uuid,
				"name", observed.name(),
				"entityTypeId", observed.entityTypeId(),
				"distance", distance,
				"lineOfSight", true,
				"tick", tick
			)));
		}
	}

	private void maintainDrowningSafetyHold(MinecraftClient client, ClientPlayerEntity player, long tick) {
		boolean shouldActuate = shouldMaintainDrowningSafetyHold(
			snapshot.state(), snapshot.cause(), player.isTouchingWater()
		);
		if (!shouldActuate) {
			if (safetyHoldActuating) {
				movementController.stop(client);
				safetyHoldActuating = false;
			}
			return;
		}
		try {
			movementController.swimUp(client, false, false, tick);
			safetyHoldActuating = true;
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("maintain_drowning_safety_hold", exception, tick);
		}
	}

	private List<ResolvedThreat> resolveThreats(MinecraftClient client, ClientPlayerEntity player) {
		if (client == null || client.world == null || player == null || observedThreats.isEmpty()) {
			return List.of();
		}
		ArrayList<ResolvedThreat> resolved = new ArrayList<>();
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity || !entity.isAlive()) {
				continue;
			}
			ObservedThreat observed = observedThreats.get(entity.getUuidAsString());
			if (observed == null) {
				continue;
			}
			double distance = player.distanceTo(entity);
			if (distance <= THREAT_CLEAR_DISTANCE) {
				resolved.add(new ResolvedThreat(observed, living, distance, player.canSee(entity)));
			}
		}
		return List.copyOf(resolved);
	}

	private static List<SurvivalReflexSnapshot.ThreatSnapshot> threatSnapshots(List<ResolvedThreat> threats) {
		if (threats == null || threats.isEmpty()) {
			return List.of();
		}
		return threats.stream().map(threat -> new SurvivalReflexSnapshot.ThreatSnapshot(
			threat.observed().uuid(), threat.observed().name(), threat.observed().entityTypeId(), threat.distance(),
			threat.entity().isAlive(), threat.lineOfSight()
		)).toList();
	}

	private void cancelSafeLandNavigation() {
		if (safeLandNavigationTarget != null && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		safeLandNavigationTarget = null;
	}

	private static String failureText(RuntimeException exception) {
		return exception == null || exception.getMessage() == null
			? exception == null ? "unknown" : exception.getClass().getSimpleName()
			: exception.getMessage();
	}

	private static Map<String, Object> mapOfNullable(Object... pairs) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index + 1 < pairs.length; index += 2) {
			if (pairs[index] != null && pairs[index + 1] != null) {
				map.put(String.valueOf(pairs[index]), pairs[index + 1]);
			}
		}
		return Map.copyOf(map);
	}

	public record DamageObservation(
		long tick,
		String damageTypeId,
		String attackerUuid,
		String attackerName,
		String attackerEntityTypeId,
		boolean attackerLiving,
		boolean attackerPlayer
	) {
	}

	public record InterruptedWork(String jobId, String actionExecutionId) {
		public static InterruptedWork none() {
			return new InterruptedWork(null, null);
		}

		public boolean hasInterruptedWork() {
			return (jobId != null && !jobId.isBlank()) || (actionExecutionId != null && !actionExecutionId.isBlank());
		}
	}

	public enum ResumeResult {
		RESUMED,
		NO_SAFETY_HOLD,
		REFLEX_ACTIVE,
		STALE_SAFETY_HOLD
	}

	record EscapeKeys(boolean forward, boolean back, boolean left, boolean right, boolean sprint, boolean jump) {
	}

	record ObservedThreat(String uuid, String name, String entityTypeId, long lastDamageTick) {
	}

	record ResolvedThreat(ObservedThreat observed, LivingEntity entity, double distance, boolean lineOfSight) {
	}
}
