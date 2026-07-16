package ai.moeru.airicraft.agent.motor;

import java.util.Objects;

public record MotorShadowObservation(MotorGraphIdentity identity, long minecraftTick, MotorFrame frame) {
	public MotorShadowObservation {
		identity = Objects.requireNonNull(identity, "identity");
		if (minecraftTick < 0) {
			throw new IllegalArgumentException("minecraftTick must be non-negative");
		}
		frame = Objects.requireNonNull(frame, "frame");
	}
}
