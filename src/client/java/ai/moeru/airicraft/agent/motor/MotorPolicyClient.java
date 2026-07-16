package ai.moeru.airicraft.agent.motor;

import java.util.concurrent.CompletableFuture;

/** Sessionful policy transport. It exposes observations and evidence, never Minecraft controls. */
public interface MotorPolicyClient extends AutoCloseable {
	CompletableFuture<MotorPolicySession> createSession(MotorSessionCreateRequest request);

	CompletableFuture<MotorPolicyStepResult> step(MotorPolicyStepRequest request);

	CompletableFuture<Void> closeSession(MotorSessionCloseRequest request);

	@Override
	default void close() {
	}
}
