package ai.moeru.airicraft.agent.actions;

public enum ActionGraphAdmission {
	STARTED,
	EXISTING,
	BUSY;

	public String id() {
		return name().toLowerCase();
	}
}
