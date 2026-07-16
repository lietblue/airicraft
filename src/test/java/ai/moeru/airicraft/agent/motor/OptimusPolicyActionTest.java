package ai.moeru.airicraft.agent.motor;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimusPolicyActionTest {
	@Test
	void requiresExactTwentyTwoKeySchema() {
		JsonObject missing = MotorShadowRuntimeTest.noopAction().toJson();
		missing.remove("forward");
		assertThrows(MotorPolicyException.class, () -> OptimusPolicyAction.fromJson(missing));

		JsonObject unknown = MotorShadowRuntimeTest.noopAction().toJson();
		unknown.addProperty("unknown", 0);
		assertThrows(MotorPolicyException.class, () -> OptimusPolicyAction.fromJson(unknown));

		JsonObject invalid = MotorShadowRuntimeTest.noopAction().toJson();
		invalid.addProperty("forward", 2);
		assertThrows(MotorPolicyException.class, () -> OptimusPolicyAction.fromJson(invalid));
	}

	@Test
	void retainsForbiddenAttemptsAndAppliesLocalShadowMaskAndAttackStabilizer() {
		JsonObject action = MotorShadowRuntimeTest.noopAction().toJson();
		action.addProperty("attack", 1);
		action.addProperty("left", 1);
		action.addProperty("use", 1);
		action.addProperty("hotbar.4", 1);
		MotorPolicyEvidence evidence = MotorPolicyEvidence.from(OptimusPolicyAction.fromJson(action));

		assertEquals(1, evidence.rawAction().left());
		assertEquals(1, evidence.rawAction().use());
		assertEquals(0, evidence.safeShadowAction().left());
		assertEquals(0, evidence.safeShadowAction().use());
		assertEquals(java.util.List.of("hotbar.4", "use"), evidence.forbiddenAttempts());
		assertEquals(java.util.List.of("left"), evidence.attackStabilizedControls());
	}
}
