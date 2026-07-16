package ai.moeru.airicraft.agent.motor;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen wire-level contract for the Stage 4 Optimus-3 shadow integration. */
public final class MotorPolicyContract {
	public static final String CONTRACT_VERSION = "airicraft.optimus3.shadow.v1";
	public static final String MODE = "shadow";
	public static final String FROZEN_PROMPT = "Mine iron ore from the environment.";
	public static final String POLICY_CONTRACT = "optimus3.policy-action-22.v1";
	public static final String MODEL_ID = "iLearn-Lab/Optimus-3";
	public static final String MODEL_REVISION = "6168839d8a44c3fab45a31354e683874d14601f3";
	public static final String ACTION_HEAD_ID = "MinecraftOptimus/Optimus-3-ActionHead";
	public static final String ACTION_HEAD_REVISION = "455e01e30c8e830f420179c93e64f54ce3b30ecc";
	public static final String EXPECTED_LABEL = "<iron>";
	public static final String TASK_EMBEDDING_SHA256 = "6ad1d2ed8fc13474afef66fafd96ef24b622ae880fffc24d9825c16c3101f4c3";
	public static final String PROJECTED_EMBEDDING_SHA256 = "b9d7cb0abcc9f5f0192f28212f4894485a6770d523a7e4bd718bdfea12a687e3";
	public static final int FRAME_WIDTH = 128;
	public static final int FRAME_HEIGHT = 128;
	public static final int FRAME_CHANNELS = 3;
	public static final int FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * FRAME_CHANNELS;
	public static final int CONTROL_DEADLINE_MILLIS = 50;

	public static final List<String> POLICY_ACTION_KEYS = List.of(
		"attack",
		"back",
		"camera",
		"forward",
		"jump",
		"left",
		"right",
		"sneak",
		"sprint",
		"ESC",
		"drop",
		"hotbar.1",
		"hotbar.2",
		"hotbar.3",
		"hotbar.4",
		"hotbar.5",
		"hotbar.6",
		"hotbar.7",
		"hotbar.8",
		"hotbar.9",
		"inventory",
		"use"
	);

	public static final List<String> FORBIDDEN_ACTION_KEYS = List.of(
		"ESC",
		"drop",
		"hotbar.1",
		"hotbar.2",
		"hotbar.3",
		"hotbar.4",
		"hotbar.5",
		"hotbar.6",
		"hotbar.7",
		"hotbar.8",
		"hotbar.9",
		"inventory",
		"use"
	);

	public static final List<String> ATTACK_STABILIZED_KEYS = List.of(
		"jump",
		"left",
		"right",
		"sneak",
		"sprint"
	);

	static final Set<String> POLICY_ACTION_KEY_SET = Set.copyOf(POLICY_ACTION_KEYS);
	private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

	private MotorPolicyContract() {
	}

	static String requireSessionId(String value) {
		String normalized = value == null ? "" : value.trim();
		if (!SESSION_ID.matcher(normalized).matches()) {
			throw new IllegalArgumentException("sessionId must be a safe 1-128 character identifier");
		}
		return normalized;
	}

	static String requireFrozenPrompt(String value) {
		if (!FROZEN_PROMPT.equals(value)) {
			throw new IllegalArgumentException("prompt must match the frozen Optimus-3 pilot prompt");
		}
		return value;
	}

	static long requireUint32Seed(long value) {
		if (value < 0L || value > 0xffff_ffffL) {
			throw new IllegalArgumentException("seed must be an unsigned 32-bit value");
		}
		return value;
	}
}
