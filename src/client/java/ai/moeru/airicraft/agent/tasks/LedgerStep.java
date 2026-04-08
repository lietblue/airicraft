package ai.moeru.airicraft.agent.tasks;

import java.util.List;

public record LedgerStep(
	String id,
	LedgerStepKind kind,
	LedgerStepPayload args,
	List<String> dependsOn,
	LedgerStepStatus status,
	List<EvidenceRequirement> expectedEvidence,
	Integer retryBudget,
	String notes
) {
	public LedgerStep {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (kind == null) {
			throw new IllegalArgumentException("kind must not be null");
		}
		if (args == null) {
			throw new IllegalArgumentException("args must not be null");
		}
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
		expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if (retryBudget == null || retryBudget < 0) {
			throw new IllegalArgumentException("retryBudget must not be negative");
		}
	}
}
