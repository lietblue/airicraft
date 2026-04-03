package ai.moeru.airicraft.agent.semantic;

import java.util.Map;
import java.util.Objects;

public record SemanticContextUpdate(
	SemanticContextUpdateKind kind,
	String text,
	long tick,
	long timestampMs,
	int sourceEventCount,
	long firstSourceSeqNo,
	long lastSourceSeqNo,
	String sourceType,
	Map<String, Object> payload,
	String aggregationKey
) {
	public SemanticContextUpdate(
		SemanticContextUpdateKind kind,
		String text,
		long tick,
		long timestampMs,
		int sourceEventCount,
		long firstSourceSeqNo,
		long lastSourceSeqNo
	) {
		this(kind, text, tick, timestampMs, sourceEventCount, firstSourceSeqNo, lastSourceSeqNo, null, Map.of(), null);
	}

	public SemanticContextUpdate {
		kind = Objects.requireNonNull(kind, "kind");
		text = Objects.requireNonNull(text, "text");
		sourceEventCount = Math.max(0, sourceEventCount);
		payload = payload == null ? Map.of() : Map.copyOf(payload);
	}
}
