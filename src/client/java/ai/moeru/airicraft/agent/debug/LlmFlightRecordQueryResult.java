package ai.moeru.airicraft.agent.debug;

import java.util.List;
import java.util.Objects;

public record LlmFlightRecordQueryResult(
	long oldestSequenceId,
	long latestSequenceId,
	boolean truncated,
	List<LlmFlightRecord> records
) {
	public LlmFlightRecordQueryResult {
		records = List.copyOf(Objects.requireNonNullElse(records, List.of()));
	}
}
