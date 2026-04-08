package ai.moeru.airicraft.agent.tasks;

public record EvidenceRequirement(
	EvidenceKind type,
	TaskResourceKind resourceKind,
	Integer quantity,
	String stepId,
	String itemId,
	String detail
) {
	public EvidenceRequirement(
		EvidenceKind type,
		TaskResourceKind resourceKind,
		Integer quantity,
		String stepId,
		String detail
	) {
		this(type, resourceKind, quantity, stepId, null, detail);
	}
}
