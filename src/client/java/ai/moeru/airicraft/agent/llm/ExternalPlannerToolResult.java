package ai.moeru.airicraft.agent.llm;

public record ExternalPlannerToolResult(
	String toolName,
	String text,
	LlmImageAttachment imageAttachment
) {
	public ExternalPlannerToolResult {
		toolName = PlannerToolCatalog.normalizeName(toolName);
		text = text == null ? "" : text;
	}

	public boolean hasImage() {
		return imageAttachment != null;
	}
}
