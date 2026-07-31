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
			return new Decision(state.withPhase(Phase.TERMINAL), Command.FAIL, "target_identity_changed");
		}
		State advanced = state.advance(observation.target());
		if (advanced.phase() == Phase.AWAIT_DELIVERY) {
			advanced = advanced.recordDroppedItems(observation.droppedItems());
			advanced = advanced.recordPickupEvidence(observation.pickups(), observation.target());
			if (advanced.deliveredQuantity() >= advanced.requestedQuantity()) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.SUCCEED, "target_picked_up_items");
			}
		}

		if (observation.target().isEmpty()) {
			if (advanced.phase() == Phase.AWAIT_DELIVERY && advanced.deliveredQuantity() > 0) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL,
					"partial_delivery delivered=" + advanced.deliveredQuantity() + " requested=" + advanced.requestedQuantity());
			}
			return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, "target_not_found");
		}

		if (advanced.phase() == Phase.SEEK_TARGET || advanced.phase() == Phase.AIMING) {
			if (observation.sourceItemCount() < advanced.requestedQuantity()) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, "insufficient_items");
			}
			if (distanceSquared(observation.agentX(), observation.agentY(), observation.agentZ(), observation.target().get())
				> APPROACH_RANGE_BLOCKS * APPROACH_RANGE_BLOCKS) {
				return new Decision(advanced.withPhase(Phase.SEEK_TARGET), Command.APPROACH, "approach_target");
			}
			if (advanced.phase() == Phase.SEEK_TARGET) {
				return new Decision(advanced.withPhase(Phase.AIMING), Command.AIM, "aim_at_target");
			}
			return new Decision(advanced.withPhase(Phase.AWAIT_DELIVERY), Command.DROP, "drop_for_target");
		}

		if (advanced.elapsedTicks() >= DELIVERY_TIMEOUT_TICKS) {
			String reason = advanced.deliveredQuantity() == 0
				? "delivery_timeout"
				: "partial_delivery delivered=" + advanced.deliveredQuantity() + " requested=" + advanced.requestedQuantity();
			return new Decision(advanced.withPhase(Phase.TERMINAL), Command.FAIL, reason);
		}
		if (distanceSquared(observation.agentX(), observation.agentY(), observation.agentZ(), observation.target().get())
			> APPROACH_RANGE_BLOCKS * APPROACH_RANGE_BLOCKS) {
			return new Decision(advanced.withPhase(Phase.AWAIT_DELIVERY), Command.APPROACH, "approach_moving_target");
		}
		return new Decision(advanced, Command.WAIT, "waiting_for_delivery_evidence");
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

	record DroppedItemEvidence(int entityId, String itemId, int count, double x, double y, double z) {
		DroppedItemEvidence {
			itemId = Objects.requireNonNull(itemId, "itemId");
			if (count <= 0) {
				throw new IllegalArgumentException("count must be positive");
			}
		}
	}

	record PickupEvidence(int entityId, String itemId, int count, int entityStackCount, UUID collectorIdentity, long observedAtTick, boolean trackedEntity) {
		PickupEvidence {
			itemId = Objects.requireNonNull(itemId, "itemId");
			if (count <= 0) {
				throw new IllegalArgumentException("count must be positive");
			}
			if (entityStackCount < 0) {
				throw new IllegalArgumentException("entityStackCount must not be negative");
			}
			if (observedAtTick < 0) {
				throw new IllegalArgumentException("observedAtTick must not be negative");
			}
		}
	}

	record State(
		Phase phase,
		UUID targetIdentity,
		String itemId,
		int requestedQuantity,
		int elapsedTicks,
		int deliveredQuantity,
		Map<Integer, Integer> observedEntityCounts,
		Map<Integer, Integer> creditedEntityCounts,
		Set<PickupKey> processedPickups
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
			Map<Integer, Integer> nextCounts = new java.util.HashMap<>(observedEntityCounts);
			for (DroppedItemEvidence item : evidence) {
				if (!itemId.equals(item.itemId())) {
					continue;
				}
				nextCounts.merge(item.entityId(), item.count(), Math::max);
			}
			return new State(phase, targetIdentity, itemId, requestedQuantity, elapsedTicks, deliveredQuantity,
				nextCounts, creditedEntityCounts, processedPickups);
		}

		State recordPickupEvidence(List<PickupEvidence> evidence, Optional<TargetObservation> target) {
			if (target.isEmpty()) {
				return this;
			}
			Map<Integer, Integer> nextObserved = new java.util.HashMap<>(observedEntityCounts);
			Map<Integer, Integer> nextCredited = new java.util.HashMap<>(creditedEntityCounts);
			Set<PickupKey> nextProcessed = new java.util.HashSet<>(processedPickups);
			int nextDelivered = deliveredQuantity;
			for (PickupEvidence pickup : evidence) {
				if (!itemId.equals(pickup.itemId()) || !target.get().identity().equals(pickup.collectorIdentity())
					|| (!pickup.trackedEntity() && !nextObserved.containsKey(pickup.entityId()))) {
					continue;
				}
				PickupKey key = new PickupKey(pickup.entityId(), pickup.itemId(), pickup.count(), pickup.collectorIdentity(), pickup.observedAtTick());
				if (!nextProcessed.add(key)) {
					continue;
				}
				nextObserved.merge(pickup.entityId(), Math.max(pickup.count(), pickup.entityStackCount()), Math::max);
				int credited = nextCredited.getOrDefault(pickup.entityId(), 0);
				int available = Math.max(0, nextObserved.get(pickup.entityId()) - credited);
				int credit = Math.min(pickup.count(), available);
				if (credit > 0) {
					nextCredited.put(pickup.entityId(), credited + credit);
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

	record PickupKey(int entityId, String itemId, int count, UUID collectorIdentity, long observedAtTick) {
	}

	record Decision(State nextState, Command command, String reason) {
		Decision {
			nextState = Objects.requireNonNull(nextState, "nextState");
			command = Objects.requireNonNull(command, "command");
			reason = Objects.requireNonNull(reason, "reason");
		}
	}
}
