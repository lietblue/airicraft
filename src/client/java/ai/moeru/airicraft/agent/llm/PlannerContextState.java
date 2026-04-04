package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;

import java.util.List;

public record PlannerContextState(
	List<PlannerContextEntry> acceptedHistoryTape,
	CompactionCheckpoint activeCheckpoint,
	List<SemanticEvent> pendingSemanticEvents,
	long pendingSemanticGapVersion,
	long nextSemanticGapVersion,
	long lastObservedEventSeqNo,
	PlannerAmbientContext lastAcceptedAmbientContext,
	long lastAcceptedTimeContextAtMs,
	boolean compactionPending,
	LlmUsageSnapshot lastObservedUsage,
	List<PlannerTrigger> queuedTriggers,
	long nextTriggerSeqNo
) {
	public static PlannerContextState initial() {
		return new PlannerContextState(
			List.of(),
			null,
			List.of(),
			0L,
			1L,
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
