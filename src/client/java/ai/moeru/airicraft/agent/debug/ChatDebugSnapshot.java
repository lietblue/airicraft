package ai.moeru.airicraft.agent.debug;

public record ChatDebugSnapshot(
	long lastAttemptTick,
	String lastAttemptText,
	String lastAttemptSource,
	boolean lastAttemptReusedPriorResponse,
	boolean lastSendSucceeded,
	long lastEmissionTick,
	String lastEmissionText,
	String lastEmissionSource
) {
	public static ChatDebugSnapshot empty() {
		return new ChatDebugSnapshot(-1L, null, null, false, false, -1L, null, null);
	}
}
