package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record TaskLedger(
	String missionId,
	MissionType missionType,
	String goalText,
	List<LedgerStep> steps,
	String activeStepId,
	List<EvidenceRequirement> completionCriteria,
	String replanReason,
	String plannerNotes
) {
	public TaskLedger {
		if (missionId == null || missionId.isBlank()) {
			throw new IllegalArgumentException("missionId must not be blank");
		}
		if (missionType == null) {
			throw new IllegalArgumentException("missionType must not be null");
		}
		steps = steps == null ? List.of() : List.copyOf(steps);
		completionCriteria = completionCriteria == null ? List.of() : List.copyOf(completionCriteria);
	}
}
