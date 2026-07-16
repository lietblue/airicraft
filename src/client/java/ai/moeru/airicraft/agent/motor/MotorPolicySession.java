package ai.moeru.airicraft.agent.motor;

import java.time.Instant;
import java.util.Objects;

public record MotorPolicySession(
	MotorGraphIdentity identity,
	String sessionId,
	long generation,
	long seed,
	String prompt,
	int recurrentResetCount,
	Instant createdAt
) {
	public MotorPolicySession {
		identity = Objects.requireNonNull(identity, "identity");
		sessionId = MotorPolicyContract.requireSessionId(sessionId);
		if (generation < 0) {
			throw new IllegalArgumentException("generation must be non-negative");
		}
		seed = MotorPolicyContract.requireUint32Seed(seed);
		prompt = MotorPolicyContract.requireFrozenPrompt(prompt);
		if (recurrentResetCount != 1) {
			throw new IllegalArgumentException("recurrentResetCount must be exactly 1");
		}
		createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}
}
