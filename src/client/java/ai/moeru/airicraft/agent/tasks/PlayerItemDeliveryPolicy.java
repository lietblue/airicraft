package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PlayerItemDeliveryPolicy {
	static final double APPROACH_RANGE_BLOCKS = 3.0D;
	static final double DELIVERY_EVIDENCE_RANGE_BLOCKS = 2.5D;
	static final int DELIVERY_TIMEOUT_TICKS = 200;

	private PlayerItemDeliveryPolicy() {
	}

	static State initial(String itemId, int quantity) {
		return new State(Phase.SEEK_TARGET, null, itemId, quantity, 0, 0, Map.of());
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
			advanced = advanced.recordDeliveryEvidence(observation.droppedItems(), observation.target());
			if (advanced.deliveredQuantity() >= advanced.requestedQuantity()) {
				return new Decision(advanced.withPhase(Phase.TERMINAL), Command.SUCCEED, "delivered_items");
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
		List<DroppedItemEvidence> droppedItems
	) {
		Observation {
			target = target == null ? Optional.empty() : target;
			droppedItems = List.copyOf(droppedItems == null ? List.of() : droppedItems);
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

	record State(
		Phase phase,
		UUID targetIdentity,
		String itemId,
		int requestedQuantity,
		int elapsedTicks,
		int deliveredQuantity,
		Map<Integer, Integer> observedEntityCounts
	) {
		State {
			phase = Objects.requireNonNull(phase, "phase");
			if (requestedQuantity <= 0) {
				throw new IllegalArgumentException("requestedQuantity must be positive");
			}
			itemId = Objects.requireNonNull(itemId, "itemId");
			observedEntityCounts = Map.copyOf(observedEntityCounts == null ? Map.of() : observedEntityCounts);
		}

		State advance(Optional<TargetObservation> target) {
			if (target.isPresent() && targetIdentity != null && !targetIdentity.equals(target.get().identity())) {
				return withPhase(Phase.TERMINAL);
			}
			return new State(phase, targetIdentity == null ? target.map(TargetObservation::identity).orElse(null) : targetIdentity,
				itemId, requestedQuantity, elapsedTicks + 1, deliveredQuantity, observedEntityCounts);
		}

		State recordDeliveryEvidence(List<DroppedItemEvidence> evidence, Optional<TargetObservation> target) {
			if (target.isEmpty()) {
				return this;
			}
			Map<Integer, Integer> nextCounts = new java.util.HashMap<>(observedEntityCounts);
			for (DroppedItemEvidence item : evidence) {
				if (!itemId.equals(item.itemId()) || distanceSquared(item.x(), item.y(), item.z(), target.get())
					> DELIVERY_EVIDENCE_RANGE_BLOCKS * DELIVERY_EVIDENCE_RANGE_BLOCKS) {
					continue;
				}
				nextCounts.merge(item.entityId(), item.count(), Math::max);
			}
			int delivered = Math.min(requestedQuantity, nextCounts.values().stream().mapToInt(Integer::intValue).sum());
			return new State(phase, targetIdentity, itemId, requestedQuantity, elapsedTicks, delivered, nextCounts);
		}

		State withPhase(Phase nextPhase) {
			return new State(nextPhase, targetIdentity, itemId, requestedQuantity, elapsedTicks, deliveredQuantity, observedEntityCounts);
		}
	}

	record Decision(State nextState, Command command, String reason) {
		Decision {
			nextState = Objects.requireNonNull(nextState, "nextState");
			command = Objects.requireNonNull(command, "command");
			reason = Objects.requireNonNull(reason, "reason");
		}
	}
}
