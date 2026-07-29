package ai.moeru.airicraft.agent.actions;

public record ActionWatchProgressObservation(boolean eligible, String pauseReason) {
	public ActionWatchProgressObservation {
		pauseReason = pauseReason == null ? "" : pauseReason;
		if (eligible) {
			pauseReason = "";
		}
	}

	public static ActionWatchProgressObservation active() {
		return new ActionWatchProgressObservation(true, "");
	}

	public static ActionWatchProgressObservation paused(String reason) {
		return new ActionWatchProgressObservation(false, reason);
	}
}
