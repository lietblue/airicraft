package ai.moeru.airicraft.agent.motor;

import java.util.Objects;

public record MotorSessionCloseRequest(MotorPolicySession session) {
	public MotorSessionCloseRequest {
		session = Objects.requireNonNull(session, "session");
	}
}
