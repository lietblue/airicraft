package ai.moeru.airicraft.agent.semantic;

import java.util.List;
import java.util.Objects;

public record SemanticContextProjectionResult(
	List<SemanticContextUpdate> updates,
	long latestObservedSeqNo
) {
	public SemanticContextProjectionResult {
		updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
	}

	public static SemanticContextProjectionResult empty(long latestObservedSeqNo) {
		return new SemanticContextProjectionResult(List.of(), latestObservedSeqNo);
	}
}
