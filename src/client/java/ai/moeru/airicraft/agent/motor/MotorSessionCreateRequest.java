package ai.moeru.airicraft.agent.motor;

import java.util.Objects;

public record MotorSessionCreateRequest(
	MotorGraphIdentity identity,
	String sessionId,
	long generation,
	long seed,
	String prompt
) {
	public MotorSessionCreateRequest {
		identity = Objects.requireNonNull(identity, "identity");
		sessionId = MotorPolicyContract.requireSessionId(sessionId);
		if (generation < 0) {
			throw new IllegalArgumentException("generation must be non-negative");
		}
		seed = MotorPolicyContract.requireUint32Seed(seed);
		prompt = MotorPolicyContract.requireFrozenPrompt(prompt);
	}

	public static MotorSessionCreateRequest create(
		MotorGraphIdentity identity,
		String sessionId,
		long generation,
		long seed
	) {
		return new MotorSessionCreateRequest(identity, sessionId, generation, seed, MotorPolicyContract.FROZEN_PROMPT);
	}
}
