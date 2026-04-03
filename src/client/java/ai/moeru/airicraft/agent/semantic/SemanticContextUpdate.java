package ai.moeru.airicraft.agent.semantic;

import java.util.Objects;

public record SemanticContextUpdate(
	SemanticContextUpdateKind kind,
	String text,
	long tick,
	long timestampMs,
	int sourceEventCount,
	long firstSourceSeqNo,
	long lastSourceSeqNo
) {
	public SemanticContextUpdate {
		kind = Objects.requireNonNull(kind, "kind");
		text = Objects.requireNonNull(text, "text");
		sourceEventCount = Math.max(0, sourceEventCount);
	}
}
