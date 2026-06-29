package ai.moeru.airicraft.agent.llm;

public record PlannerChatMessage(
	String text,
	int delayTicks
) {
	public PlannerChatMessage {
		text = text == null ? "" : text;
		delayTicks = Math.max(0, delayTicks);
	}

	public static PlannerChatMessage immediate(String text) {
		return new PlannerChatMessage(text, 0);
	}
}
