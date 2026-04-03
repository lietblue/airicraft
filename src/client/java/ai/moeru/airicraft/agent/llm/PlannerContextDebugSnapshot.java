package ai.moeru.airicraft.agent.llm;

public record PlannerContextDebugSnapshot(
	int compactionTriggerTokens,
	boolean compactionPending,
	int rawArchiveEntryCount,
	int acceptedConversationMessageCount,
	int pendingSemanticEventCount,
	int projectedPendingNoticeCount,
	int frozenPlannerMessageCount,
	int queuedTriggerCount,
	long lastObservedEventSeqNo,
	long lastAcceptedTimeBeaconAtMs,
	boolean pendingSemanticGap,
	LlmUsageSnapshot lastObservedUsage,
	PlannerAmbientContext acceptedAmbientContext,
	CompactionCheckpoint activeCheckpoint
) {
}
