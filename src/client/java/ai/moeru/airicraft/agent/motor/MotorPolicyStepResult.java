package ai.moeru.airicraft.agent.motor;

import java.time.Instant;
import java.util.Objects;

/** Metadata and action evidence only; image bytes are deliberately absent. */
public record MotorPolicyStepResult(
	MotorGraphIdentity identity,
	String sessionId,
	long generation,
	long stepIndex,
	long seed,
	String prompt,
	long minecraftTick,
	long frameId,
	long capturedAtMs,
	String encodedFrameSha256,
	String decodedPixelsSha256,
	boolean actuationAuthorized,
	MotorPolicyEvidence evidence,
	MotorPolicyServiceTiming serviceTiming,
	long endToEndLatencyNanos,
	Instant receivedAt
) {
	public MotorPolicyStepResult {
		identity = Objects.requireNonNull(identity, "identity");
		sessionId = MotorPolicyContract.requireSessionId(sessionId);
		if (generation < 0 || stepIndex < 0 || minecraftTick < 0 || frameId <= 0 || capturedAtMs < 0) {
			throw new IllegalArgumentException("result sequence and capture values are invalid");
		}
		seed = MotorPolicyContract.requireUint32Seed(seed);
		prompt = MotorPolicyContract.requireFrozenPrompt(prompt);
		if (actuationAuthorized) {
			throw new IllegalArgumentException("Stage 4 is shadow-only; actuationAuthorized must be false");
		}
		evidence = Objects.requireNonNull(evidence, "evidence");
		serviceTiming = Objects.requireNonNull(serviceTiming, "serviceTiming");
		if (endToEndLatencyNanos < 0L) {
			throw new IllegalArgumentException("endToEndLatencyNanos must not be negative");
		}
		receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
	}
}
