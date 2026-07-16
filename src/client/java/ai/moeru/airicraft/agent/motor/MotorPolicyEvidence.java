package ai.moeru.airicraft.agent.motor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shadow-only local normalization evidence. No field authorizes input actuation. */
public record MotorPolicyEvidence(
	OptimusPolicyAction rawAction,
	OptimusPolicyAction safeShadowAction,
	List<String> forbiddenAttempts,
	List<String> attackStabilizedControls
) {
	public MotorPolicyEvidence {
		rawAction = Objects.requireNonNull(rawAction, "rawAction");
		safeShadowAction = Objects.requireNonNull(safeShadowAction, "safeShadowAction");
		forbiddenAttempts = List.copyOf(forbiddenAttempts);
		attackStabilizedControls = List.copyOf(attackStabilizedControls);
	}

	public static MotorPolicyEvidence from(OptimusPolicyAction rawAction) {
		Objects.requireNonNull(rawAction, "rawAction");
		List<String> forbidden = new ArrayList<>();
		for (String key : MotorPolicyContract.FORBIDDEN_ACTION_KEYS) {
			if (rawAction.value(key) == 1) {
				forbidden.add(key);
			}
		}
		List<String> stabilized = new ArrayList<>();
		if (rawAction.attack() == 1) {
			for (String key : MotorPolicyContract.ATTACK_STABILIZED_KEYS) {
				if (rawAction.value(key) == 1) {
					stabilized.add(key);
				}
			}
		}
		return new MotorPolicyEvidence(rawAction, rawAction.stabilizedAndMasked(), forbidden, stabilized);
	}
}
