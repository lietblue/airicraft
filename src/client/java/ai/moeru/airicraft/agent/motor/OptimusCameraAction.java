package ai.moeru.airicraft.agent.motor;

public record OptimusCameraAction(double pitch, double yaw) {
	public OptimusCameraAction {
		if (!Double.isFinite(pitch) || !Double.isFinite(yaw)) {
			throw new IllegalArgumentException("camera action must be finite");
		}
	}
}
