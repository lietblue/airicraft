package ai.moeru.airicraft.agent.debug;

public record CollectResourceTaskDebugSnapshot(
	boolean active,
	String jobId,
	String resourceKind,
	int baselineResourceCount,
	int currentResourceCount,
	int inventoryDelta,
	int targetQuantity,
	int collected,
	int remaining,
	boolean nearbyResourceTargetAvailable,
	String primitiveExecutionState,
	String activeJobStatus,
	String blockedReason,
	String completionReason,
	long updatedTick
) {
	public static CollectResourceTaskDebugSnapshot empty() {
		return new CollectResourceTaskDebugSnapshot(
			false,
			null,
			null,
			0,
			0,
			0,
			0,
			0,
			0,
			false,
			null,
			null,
			null,
			null,
			-1L
		);
	}
}
