package ai.moeru.airicraft.agent.motor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A fully validated, exact 22-key Optimus-3 policy output. */
public record OptimusPolicyAction(
	int attack,
	int back,
	OptimusCameraAction camera,
	int forward,
	int jump,
	int left,
	int right,
	int sneak,
	int sprint,
	int escape,
	int drop,
	int hotbar1,
	int hotbar2,
	int hotbar3,
	int hotbar4,
	int hotbar5,
	int hotbar6,
	int hotbar7,
	int hotbar8,
	int hotbar9,
	int inventory,
	int use
) {
	public OptimusPolicyAction {
		binary(attack, "attack");
		binary(back, "back");
		camera = Objects.requireNonNull(camera, "camera");
		binary(forward, "forward");
		binary(jump, "jump");
		binary(left, "left");
		binary(right, "right");
		binary(sneak, "sneak");
		binary(sprint, "sprint");
		binary(escape, "ESC");
		binary(drop, "drop");
		binary(hotbar1, "hotbar.1");
		binary(hotbar2, "hotbar.2");
		binary(hotbar3, "hotbar.3");
		binary(hotbar4, "hotbar.4");
		binary(hotbar5, "hotbar.5");
		binary(hotbar6, "hotbar.6");
		binary(hotbar7, "hotbar.7");
		binary(hotbar8, "hotbar.8");
		binary(hotbar9, "hotbar.9");
		binary(inventory, "inventory");
		binary(use, "use");
	}

	public static OptimusPolicyAction fromJson(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			throw MotorPolicyException.schema("action must be an object");
		}
		JsonObject object = element.getAsJsonObject();
		Set<String> actual = object.keySet();
		if (!actual.equals(MotorPolicyContract.POLICY_ACTION_KEY_SET)) {
			throw MotorPolicyException.schema("action must contain the exact 22-key policy schema");
		}
		JsonElement cameraElement = object.get("camera");
		if (cameraElement == null || !cameraElement.isJsonArray()) {
			throw MotorPolicyException.schema("camera must be a two-number array");
		}
		JsonArray camera = cameraElement.getAsJsonArray();
		if (camera.size() != 2) {
			throw MotorPolicyException.schema("camera must be a two-number array");
		}
		return new OptimusPolicyAction(
			discrete(object, "attack"),
			discrete(object, "back"),
			new OptimusCameraAction(number(camera.get(0), "camera"), number(camera.get(1), "camera")),
			discrete(object, "forward"),
			discrete(object, "jump"),
			discrete(object, "left"),
			discrete(object, "right"),
			discrete(object, "sneak"),
			discrete(object, "sprint"),
			discrete(object, "ESC"),
			discrete(object, "drop"),
			discrete(object, "hotbar.1"),
			discrete(object, "hotbar.2"),
			discrete(object, "hotbar.3"),
			discrete(object, "hotbar.4"),
			discrete(object, "hotbar.5"),
			discrete(object, "hotbar.6"),
			discrete(object, "hotbar.7"),
			discrete(object, "hotbar.8"),
			discrete(object, "hotbar.9"),
			discrete(object, "inventory"),
			discrete(object, "use")
		);
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		for (Map.Entry<String, Object> entry : asProtocolMap().entrySet()) {
			if (entry.getValue() instanceof OptimusCameraAction cameraAction) {
				JsonArray cameraJson = new JsonArray();
				cameraJson.add(cameraAction.pitch());
				cameraJson.add(cameraAction.yaw());
				object.add(entry.getKey(), cameraJson);
			}
			else {
				object.addProperty(entry.getKey(), (Integer) entry.getValue());
			}
		}
		return object;
	}

	public Map<String, Object> asProtocolMap() {
		LinkedHashMap<String, Object> values = new LinkedHashMap<>();
		values.put("attack", attack);
		values.put("back", back);
		values.put("camera", camera);
		values.put("forward", forward);
		values.put("jump", jump);
		values.put("left", left);
		values.put("right", right);
		values.put("sneak", sneak);
		values.put("sprint", sprint);
		values.put("ESC", escape);
		values.put("drop", drop);
		values.put("hotbar.1", hotbar1);
		values.put("hotbar.2", hotbar2);
		values.put("hotbar.3", hotbar3);
		values.put("hotbar.4", hotbar4);
		values.put("hotbar.5", hotbar5);
		values.put("hotbar.6", hotbar6);
		values.put("hotbar.7", hotbar7);
		values.put("hotbar.8", hotbar8);
		values.put("hotbar.9", hotbar9);
		values.put("inventory", inventory);
		values.put("use", use);
		return Map.copyOf(values);
	}

	public int value(String key) {
		return switch (key) {
			case "attack" -> attack;
			case "back" -> back;
			case "forward" -> forward;
			case "jump" -> jump;
			case "left" -> left;
			case "right" -> right;
			case "sneak" -> sneak;
			case "sprint" -> sprint;
			case "ESC" -> escape;
			case "drop" -> drop;
			case "hotbar.1" -> hotbar1;
			case "hotbar.2" -> hotbar2;
			case "hotbar.3" -> hotbar3;
			case "hotbar.4" -> hotbar4;
			case "hotbar.5" -> hotbar5;
			case "hotbar.6" -> hotbar6;
			case "hotbar.7" -> hotbar7;
			case "hotbar.8" -> hotbar8;
			case "hotbar.9" -> hotbar9;
			case "inventory" -> inventory;
			case "use" -> use;
			default -> throw new IllegalArgumentException("Not a discrete policy key: " + key);
		};
	}

	OptimusPolicyAction stabilizedAndMasked() {
		boolean attacking = attack == 1;
		return new OptimusPolicyAction(
			attack,
			back,
			camera,
			forward,
			attacking ? 0 : jump,
			attacking ? 0 : left,
			attacking ? 0 : right,
			attacking ? 0 : sneak,
			attacking ? 0 : sprint,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
		);
	}

	private static int discrete(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			throw MotorPolicyException.schema("discrete action must be numeric 0 or 1: " + key);
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			throw MotorPolicyException.schema("discrete action must be numeric 0 or 1: " + key);
		}
		double value = number(element, key);
		if (value != 0.0d && value != 1.0d) {
			throw MotorPolicyException.schema("discrete action must be numeric 0 or 1: " + key);
		}
		return (int) value;
	}

	private static double number(JsonElement element, String key) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw MotorPolicyException.schema("action value must be a finite number: " + key);
		}
		try {
			double value = element.getAsDouble();
			if (!Double.isFinite(value)) {
				throw MotorPolicyException.schema("action value must be a finite number: " + key);
			}
			return value;
		}
		catch (NumberFormatException exception) {
			throw MotorPolicyException.schema("action value must be a finite number: " + key);
		}
	}

	private static void binary(int value, String key) {
		if (value != 0 && value != 1) {
			throw new IllegalArgumentException(key + " must be 0 or 1");
		}
	}
}
