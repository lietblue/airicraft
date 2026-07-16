package ai.moeru.airicraft.agent.motor;

import java.util.Objects;

public record MotorPolicyStepRequest(
	MotorGraphIdentity identity,
	String sessionId,
	long generation,
	long stepIndex,
	long seed,
	String prompt,
	long minecraftTick,
	MotorFrame frame,
	boolean actuationAuthorized
) {
	public MotorPolicyStepRequest {
		identity = Objects.requireNonNull(identity, "identity");
		sessionId = MotorPolicyContract.requireSessionId(sessionId);
		if (generation < 0 || stepIndex < 0 || minecraftTick < 0) {
			throw new IllegalArgumentException("generation, stepIndex, and minecraftTick must be non-negative");
		}
		seed = MotorPolicyContract.requireUint32Seed(seed);
		prompt = MotorPolicyContract.requireFrozenPrompt(prompt);
		frame = Objects.requireNonNull(frame, "frame");
		if (actuationAuthorized) {
			throw new IllegalArgumentException("Stage 4 is shadow-only; actuationAuthorized must be false");
		}
	}

	@Override
	public String toString() {
		return "MotorPolicyStepRequest[identity=" + identity
			+ ", sessionId=" + sessionId
			+ ", generation=" + generation
			+ ", stepIndex=" + stepIndex
			+ ", seed=" + seed
			+ ", prompt=" + prompt
			+ ", minecraftTick=" + minecraftTick
			+ ", frame=" + frame.snapshot()
			+ ", actuationAuthorized=false]";
	}
}
