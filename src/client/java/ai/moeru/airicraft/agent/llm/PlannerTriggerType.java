package ai.moeru.airicraft.agent.llm;

public enum PlannerTriggerType {
	CHAT("chat"),
	CRAFT("craft"),
	SYSTEM("system");

	private final String promptLabel;

	PlannerTriggerType(String promptLabel) {
		this.promptLabel = promptLabel;
	}

	public String promptLabel() {
		return promptLabel;
	}
}
