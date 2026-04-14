package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;

public record PlannerAttemptDebugSnapshot(
	long submissionId,
	long generation,
	int attempt,
	String phase,
	String status,
	long submittedAtMs,
	long completedAtMs,
	long latencyMs,
	String failureType,
	String summary,
	LlmConversation canonicalConversation,
	PlannerResponse plannerResponse,
	String failureMessage
) {
	public static PlannerAttemptDebugSnapshot submitted(
		long submissionId,
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		long submittedAtMs,
		String summary,
		LlmConversation canonicalConversation
	) {
		return new PlannerAttemptDebugSnapshot(
			submissionId,
			generation,
			attempt,
			phase == null ? "UNKNOWN" : phase.name(),
			"SUBMITTED",
			submittedAtMs,
			0L,
			0L,
			null,
			summary,
			canonicalConversation,
			null,
			null
		);
	}

	public PlannerAttemptDebugSnapshot completed(long completedAtMs, String status, String failureType, String summary, PlannerResponse response, String failureMessage) {
		long safeCompletedAtMs = Math.max(completedAtMs, submittedAtMs);
		return new PlannerAttemptDebugSnapshot(
			submissionId,
			generation,
			attempt,
			phase,
			status,
			submittedAtMs,
			safeCompletedAtMs,
			safeCompletedAtMs - submittedAtMs,
			failureType,
			summary,
			canonicalConversation,
			response,
			failureMessage
		);
	}
}
