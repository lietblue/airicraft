package ai.moeru.airicraft.agent.llm;

public record PlannerContextDebugSnapshot(
	int compactionTriggerTokens,
	boolean compactionPending,
	int acceptedTurnCount,
	int pendingSemanticEventCount,
	int projectedPendingNoticeCount,
	int frozenPlannerMessageCount,
	int queuedTriggerCount,
	long lastObservedEventSeqNo,
	long lastAcceptedTimeContextAtMs,
	boolean pendingSemanticGap,
	boolean overflowFlushPending,
	LlmUsageSnapshot lastObservedUsage,
	PlannerAmbientContext acceptedAmbientContext,
	CompactionCheckpoint activeCheckpoint
) {
}
