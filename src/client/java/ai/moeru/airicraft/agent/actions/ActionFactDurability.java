package ai.moeru.airicraft.agent.actions;

public enum ActionFactDurability {
	VOLATILE(false),
	PERSISTENT(true);

	private final boolean persistedByDefault;

	ActionFactDurability(boolean persistedByDefault) {
		this.persistedByDefault = persistedByDefault;
	}

	public boolean persistedByDefault() {
		return persistedByDefault;
	}
}
