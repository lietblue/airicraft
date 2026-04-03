package ai.moeru.airicraft.agent.llm;

import java.util.List;

public record PlannerContextState(
	List<PlannerContextEntry> rawArchiveTape,
	List<LlmChatMessage> canonicalTape,
	CompactionCheckpoint activeCheckpoint,
	List<PlannerContextEntry> pendingEntries,
	long lastObservedEventSeqNo,
	PlannerAmbientContext lastAmbientContext,
	long lastTimeBeaconAtMs,
	boolean compactionPending,
	LlmUsageSnapshot lastObservedUsage,
	List<PlannerTrigger> queuedTriggers,
	long nextTriggerSeqNo
) {
	public static PlannerContextState initial() {
		return new PlannerContextState(
			List.of(),
			List.of(),
			null,
			List.of(),
			0L,
			null,
			-1L,
			false,
			LlmUsageSnapshot.unknown(),
			List.of(),
			1L
		);
	}
}
