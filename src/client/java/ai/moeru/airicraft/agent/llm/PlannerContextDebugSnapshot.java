package ai.moeru.airicraft.agent.llm;

public record PlannerContextDebugSnapshot(
	int compactionTriggerTokens,
	boolean compactionPending,
	int rawArchiveEntryCount,
	int canonicalMessageCount,
	int pendingEntryCount,
	int frozenPlannerMessageCount,
	int queuedTriggerCount,
	long lastObservedEventSeqNo,
	long lastTimeBeaconAtMs,
	LlmUsageSnapshot lastObservedUsage,
	PlannerAmbientContext ambientContext,
	CompactionCheckpoint activeCheckpoint
) {
}
