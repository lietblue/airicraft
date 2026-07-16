package ai.moeru.airicraft.agent.motor;

import java.util.concurrent.CompletableFuture;

public interface MotorFrameSource {
	CompletableFuture<MotorFrame> requestCapture();

	boolean cancelActiveCapture();

	MotorFrameCaptureSnapshot snapshot();
}
