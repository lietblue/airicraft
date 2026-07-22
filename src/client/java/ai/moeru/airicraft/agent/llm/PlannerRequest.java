package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;

import java.util.List;

public record PlannerRequest(
	long tick,
	long timestampMs,
	SessionMode sessionMode,
	String primaryInteractionPlayer,
	GoalSnapshot activeGoal,
	TaskSnapshot activeTask,
	MissionExecutionSnapshot missionExecution,
	PlannerTriggerBatch triggerBatch,
	String toolResult,
	long safetyEpoch,
	String safetyHoldId
) {
	public PlannerRequest(
		long tick,
		long timestampMs,
		SessionMode sessionMode,
		String primaryInteractionPlayer,
		GoalSnapshot activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		PlannerTriggerBatch triggerBatch,
		String toolResult
	) {
		this(tick, timestampMs, sessionMode, primaryInteractionPlayer, activeGoal, activeTask, missionExecution, triggerBatch, toolResult, 0L, null);
	}
	public PlannerRequest(
		long tick,
		long timestampMs,
		SessionMode sessionMode,
		String primaryInteractionPlayer,
		GoalSnapshot activeGoal,
		String senderName,
		String message,
		String toolResult
	) {
		this(
			tick,
			timestampMs,
			sessionMode,
			primaryInteractionPlayer,
			activeGoal,
			null,
			null,
			PlannerTriggerBatch.of(List.of(
				PlannerTrigger.pending(PlannerTriggerType.CHAT, senderName, message, tick, timestampMs)
			)),
			toolResult
		);
	}

	public PlannerRequest(
		long tick,
		long timestampMs,
		SessionMode sessionMode,
		String primaryInteractionPlayer,
		GoalSnapshot activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		String senderName,
		String message,
		String toolResult
	) {
		this(
			tick,
			timestampMs,
			sessionMode,
			primaryInteractionPlayer,
			activeGoal,
			activeTask,
			missionExecution,
			PlannerTriggerBatch.of(List.of(
				PlannerTrigger.pending(PlannerTriggerType.CHAT, senderName, message, tick, timestampMs)
			)),
			toolResult
		);
	}

	public static PlannerRequest ofTrigger(
		long tick,
		long timestampMs,
		SessionMode sessionMode,
		String primaryInteractionPlayer,
		GoalSnapshot activeGoal,
		PlannerTriggerType triggerType,
		String speaker,
		String message,
		String toolResult
	) {
		return new PlannerRequest(
			tick,
			timestampMs,
			sessionMode,
			primaryInteractionPlayer,
			activeGoal,
			null,
			null,
			PlannerTriggerBatch.of(List.of(
				PlannerTrigger.pending(triggerType, speaker, message, tick, timestampMs)
			)),
			toolResult
		);
	}

	public PlannerRequest withTriggerBatch(PlannerTriggerBatch replacementBatch) {
		return new PlannerRequest(tick, timestampMs, sessionMode, primaryInteractionPlayer, activeGoal, activeTask, missionExecution, replacementBatch, toolResult, safetyEpoch, safetyHoldId);
	}

	public PlannerRequest withToolResult(String replacementToolResult) {
		return new PlannerRequest(tick, timestampMs, sessionMode, primaryInteractionPlayer, activeGoal, activeTask, missionExecution, triggerBatch, replacementToolResult, safetyEpoch, safetyHoldId);
	}

	public PlannerRequest withSafetyContext(long replacementSafetyEpoch, String replacementSafetyHoldId) {
		return new PlannerRequest(
			tick, timestampMs, sessionMode, primaryInteractionPlayer, activeGoal, activeTask, missionExecution,
			triggerBatch, toolResult, Math.max(0L, replacementSafetyEpoch), replacementSafetyHoldId
		);
	}

	public String senderName() {
		return triggerBatch == null ? null : triggerBatch.primarySpeaker();
	}

	public String message() {
		return triggerBatch == null ? "" : triggerBatch.primaryMessage();
	}
}
