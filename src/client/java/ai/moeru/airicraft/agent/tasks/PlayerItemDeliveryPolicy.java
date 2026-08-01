package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class PlayerItemDeliveryPolicy {
	static final double APPROACH_RANGE_BLOCKS = 3.0D;
	static final int DELIVERY_TIMEOUT_TICKS = 200;

	private PlayerItemDeliveryPolicy() {
	}

	static State initial(String itemId, int quantity) {
		return new State(Phase.SEEK_TARGET, null, itemId, quantity, 0, 0, Map.of(), Map.of(), Set.of());
	}

	static Decision decide(State state, Observation observation) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(observation, "observation");
		if (observation.target().isPresent() && state.targetIdentity() != null
			&& !state.targetIdentity().equals(observation.target().get().identity())) {
			return new Decision(state.withPhase(Phase.TERMINAL), Command.FAIL, "target_identity_changed", TaskFailureCode.UNKNOWN);
		}
		State advanced = state.advance(observation.target());
		if (advanced.phase() == Phase.AWAIT_DELIVERY) {
			advanced = advanced.recordDroppedItems(observation.droppedItems());
			advanced = advanced.recordPickupEvidence(observation.pickups(), observation.target());
			if (advanced.deliveredQuantity() >= advanced.requestedQuantity()) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.SUCCEED, "target_picked_up_items", TaskFailureCode.NONE);
			}
		}

		if (observation.target().isEmpty()) {
			if (advanced.phase() == Phase.AWAIT_DELIVERY && advanced.deliveredQuantity() > 0) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL,
					"partial_delivery delivered=" + advanced.deliveredQuantity() + " requested=" + advanced.requestedQuantity(), TaskFailureCode.UNKNOWN);
			}
			return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, "target_not_found", TaskFailureCode.MISSING_FACT);
		}

		if (advanced.phase() == Phase.SEEK_TARGET || advanced.phase() == Phase.AIMING) {
			if (observation.sourceItemCount() < advanced.requestedQuantity()) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, "insufficient_items", TaskFailureCode.UNKNOWN);
			}
			if (distanceSquared(observation.agentX(), observation.agentY(), observation.agentZ(), observation.target().get())
				> APPROACH_RANGE_BLOCKS * APPROACH_RANGE_BLOCKS) {
				return new Decision(advanced.withPhase(Phase.SEEK_TARGET), Command.APPROACH, "approach_target", TaskFailureCode.NONE);
			}
			if (advanced.phase() == Phase.SEEK_TARGET) {
				return new Decision(advanced.withPhase(Phase.AIMING), Command.AIM, "aim_at_target", TaskFailureCode.NONE);
			}
			return new Decision(advanced.withPhase(Phase.AWAIT_DELIVERY), Command.DROP, "drop_for_target", TaskFailureCode.NONE);
		}

		if (advanced.elapsedTicks() >= DELIVERY_TIMEOUT_TICKS) {
			String reason = advanced.deliveredQuantity() == 0
				? "delivery_timeout"
				: "partial_delivery delivered=" + advanced.deliveredQuantity() + " requested=" + advanced.requestedQuantity();
			return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, reason,
				advanced.deliveredQuantity() == 0 ? TaskFailureCode.TRANSIENT : TaskFailureCode.UNKNOWN);
		}
		if (distanceSquared(observation.agentX(), observation.agentY(), observation.agentZ(), observation.target().get())
			> APPROACH_RANGE_BLOCKS * APPROACH_RANGE_BLOCKS) {
			return new Decision(advanced.withPhase(Phase.AWAIT_DELIVERY), Command.APPROACH, "approach_moving_target", TaskFailureCode.NONE);
		}
		return new Decision(advanced, Command.WAIT, "waiting_for_delivery_evidence", TaskFailureCode.NONE);
	}

	private static double distanceSquared(double agentX, double agentY, double agentZ, TargetObservation target) {
		double dx = agentX - target.x();
		double dy = agentY - target.y();
		double dz = agentZ - target.z();
		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	enum Command {
		APPROACH,
		AIM,
		DROP,
		WAIT,
		SUCCEED,
		FAIL
	}

	enum Phase {
		SEEK_TARGET,
		AIMING,
		AWAIT_DELIVERY,
		TERMINAL
	}

	record Observation(
		double agentX,
		double agentY,
		double agentZ,
		int sourceItemCount,
		Optional<TargetObservation> target,
		List<DroppedItemEvidence> droppedItems,
		List<PickupEvidence> pickups
	) {
		Observation {
			target = target == null ? Optional.empty() : target;
			droppedItems = List.copyOf(droppedItems == null ? List.of() : droppedItems);
			pickups = List.copyOf(pickups == null ? List.of() : pickups);
		}
	}

	record TargetObservation(UUID identity, String name, double x, double y, double z) {
		TargetObservation {
			identity = Objects.requireNonNull(identity, "identity");
			name = Objects.requireNonNull(name, "name");
		}
	}

	record DroppedItemEvidence(EntityGeneration generation, String itemId, int count, double x, double y, double z) {
		DroppedItemEvidence(int entityId, String itemId, int count, double x, double y, double z) {
			this(new EntityGeneration(entityId, UUID.nameUUIDFromBytes(("entity:" + entityId).getBytes(java.nio.charset.StandardCharsets.UTF_8))), itemId, count, x, y, z);
		}
		DroppedItemEvidence {
			itemId = Objects.requireNonNull(itemId, "itemId");
			if (count <= 0) {
				throw new IllegalArgumentException("count must be positive");
			}
		}
	}

	record PickupEvidence(
		EntityGeneration generation,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
		PickupEvidence(int entityId, String itemId, int pickupDelta, int agentAttributedQuantity,
			int ignoredObservedEntityStackCount, UUID collectorIdentity, long ignoredObservedAtTick, boolean ignoredTrackedEntity) {
			this(new EntityGeneration(entityId, UUID.nameUUIDFromBytes(("entity:" + entityId).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
				itemId, pickupDelta, agentAttributedQuantity, collectorIdentity,
				UUID.nameUUIDFromBytes((entityId + ":" + ignoredObservedAtTick + ":" + pickupDelta + ":" + agentAttributedQuantity)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		PickupEvidence {
			generation = Objects.requireNonNull(generation, "generation");
			itemId = Objects.requireNonNull(itemId, "itemId");
			if (pickupDelta <= 0) {
				throw new IllegalArgumentException("pickupDelta must be positive");
			}
			if (agentAttributedQuantity <= 0) {
				throw new IllegalArgumentException("agentAttributedQuantity must be positive");
			}
			observationId = Objects.requireNonNull(observationId, "observationId");
		}
	}

	record State(
		Phase phase,
		UUID targetIdentity,
		String itemId,
		int requestedQuantity,
		int elapsedTicks,
		int deliveredQuantity,
		Map<EntityGeneration, Integer> observedEntityCounts,
		Map<EntityGeneration, Integer> creditedEntityCounts,
		Set<UUID> processedPickups
	) {
		State {
			phase = Objects.requireNonNull(phase, "phase");
			if (requestedQuantity <= 0) {
				throw new IllegalArgumentException("requestedQuantity must be positive");
			}
			itemId = Objects.requireNonNull(itemId, "itemId");
			observedEntityCounts = Map.copyOf(observedEntityCounts == null ? Map.of() : observedEntityCounts);
			creditedEntityCounts = Map.copyOf(creditedEntityCounts == null ? Map.of() : creditedEntityCounts);
			processedPickups = Set.copyOf(processedPickups == null ? Set.of() : processedPickups);
		}

		State advance(Optional<TargetObservation> target) {
			if (target.isPresent() && targetIdentity != null && !targetIdentity.equals(target.get().identity())) {
				return withPhase(Phase.TERMINAL);
			}
			return new State(phase, targetIdentity == null ? target.map(TargetObservation::identity).orElse(null) : targetIdentity,
				itemId, requestedQuantity, elapsedTicks + 1, deliveredQuantity, observedEntityCounts, creditedEntityCounts, processedPickups);
		}

		State recordDroppedItems(List<DroppedItemEvidence> evidence) {
			Map<EntityGeneration, Integer> nextCounts = new java.util.HashMap<>(observedEntityCounts);
			for (DroppedItemEvidence item : evidence) {
				if (!itemId.equals(item.itemId())) {
					continue;
				}
				nextCounts.merge(item.generation(), item.count(), Math::max);
			}
			return new State(phase, targetIdentity, itemId, requestedQuantity, elapsedTicks, deliveredQuantity,
				nextCounts, creditedEntityCounts, processedPickups);
		}

		State recordPickupEvidence(List<PickupEvidence> evidence, Optional<TargetObservation> target) {
			if (target.isEmpty()) {
				return this;
			}
			Map<EntityGeneration, Integer> nextObserved = new java.util.HashMap<>(observedEntityCounts);
			Map<EntityGeneration, Integer> nextCredited = new java.util.HashMap<>(creditedEntityCounts);
			Set<UUID> nextProcessed = new java.util.HashSet<>(processedPickups);
			int nextDelivered = deliveredQuantity;
			for (PickupEvidence pickup : evidence) {
				if (!itemId.equals(pickup.itemId()) || !target.get().identity().equals(pickup.collectorIdentity())
					|| (!nextObserved.containsKey(pickup.generation()) && pickup.agentAttributedQuantity() <= 0)) {
					continue;
				}
				if (!nextProcessed.add(pickup.observationId())) {
					continue;
				}
				nextObserved.merge(pickup.generation(), pickup.agentAttributedQuantity(), Math::max);
				int credited = nextCredited.getOrDefault(pickup.generation(), 0);
				int available = Math.max(0, nextObserved.get(pickup.generation()) - credited);
				int credit = Math.min(pickup.pickupDelta(), available);
				if (credit > 0) {
				nextCredited.put(pickup.generation(), credited + credit);
					nextDelivered = Math.min(requestedQuantity, nextDelivered + credit);
				}
			}
			return new State(phase, targetIdentity, itemId, requestedQuantity, elapsedTicks, nextDelivered,
				nextObserved, nextCredited, nextProcessed);
		}

		State withPhase(Phase nextPhase) {
			return new State(nextPhase, targetIdentity, itemId, requestedQuantity, elapsedTicks, deliveredQuantity,
				observedEntityCounts, creditedEntityCounts, processedPickups);
		}
	}

	record EntityGeneration(
		int entityId,
		UUID entityUuid
	) {
		EntityGeneration {
			entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
		}
	}

	record Decision(State nextState, Command command, String reason, TaskFailureCode failureCode) {
		Decision {
			nextState = Objects.requireNonNull(nextState, "nextState");
			command = Objects.requireNonNull(command, "command");
			reason = Objects.requireNonNull(reason, "reason");
			failureCode = Objects.requireNonNull(failureCode, "failureCode");
		}
	}
}
