package ai.moeru.airicraft.agent.llm;

final class PlannerSession {
	private final long generation;
	private final PlannerContextSnapshot contextSnapshot;
	private PlannerRequest request;
	private PlannerSessionPhase phase;
	private LlmConversation conversation;
	private int attemptCount;
	private long retryReadyAtMs = -1L;

	PlannerSession(long generation, PlannerContextSnapshot contextSnapshot) {
		this.generation = generation;
		this.contextSnapshot = contextSnapshot;
		this.request = contextSnapshot.request();
		this.phase = PlannerSessionPhase.PLANNER_REQUEST;
		this.conversation = contextSnapshot.plannerConversation();
	}

	long generation() {
		return generation;
	}

	PlannerContextSnapshot contextSnapshot() {
		return contextSnapshot;
	}

	PlannerRequest request() {
		return request;
	}

	PlannerSessionPhase phase() {
		return phase;
	}

	LlmConversation conversation() {
		return conversation;
	}

	int attemptCount() {
		return attemptCount;
	}

	long retryReadyAtMs() {
		return retryReadyAtMs;
	}

	boolean retryPending() {
		return retryReadyAtMs >= 0L;
	}

	boolean readyForRetry(long nowMs) {
		return retryPending() && nowMs >= retryReadyAtMs;
	}

	boolean awaitingLaunch() {
		return conversation != null && attemptCount == 0 && !retryPending();
	}

	boolean replaceable() {
		return phase != PlannerSessionPhase.COMPLETED
			&& phase != PlannerSessionPhase.FAILED
			&& phase != PlannerSessionPhase.SUPERSEDED;
	}

	void beginAttempt() {
		attemptCount++;
		retryReadyAtMs = -1L;
	}

	void scheduleRetry(long whenMs) {
		retryReadyAtMs = whenMs;
	}

	void clearRetry() {
		retryReadyAtMs = -1L;
	}

	void moveToToolWait() {
		phase = PlannerSessionPhase.TOOL_WAIT;
		conversation = null;
		attemptCount = 0;
		retryReadyAtMs = -1L;
	}

	void moveToToolFollowUp(PlannerRequest replacementRequest, LlmConversation replacementConversation) {
		request = replacementRequest;
		phase = PlannerSessionPhase.TOOL_FOLLOW_UP;
		conversation = replacementConversation;
		attemptCount = 0;
		retryReadyAtMs = -1L;
	}

	void markSuperseded() {
		phase = PlannerSessionPhase.SUPERSEDED;
		conversation = null;
		retryReadyAtMs = -1L;
	}

	void markCompleted() {
		phase = PlannerSessionPhase.COMPLETED;
		conversation = null;
		retryReadyAtMs = -1L;
	}

	void markFailed() {
		phase = PlannerSessionPhase.FAILED;
		conversation = null;
		retryReadyAtMs = -1L;
	}

	PlannerSessionSnapshot snapshot() {
		return new PlannerSessionSnapshot(generation, phase, attemptCount, retryPending(), retryReadyAtMs, request);
	}
}
