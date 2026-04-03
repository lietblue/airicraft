package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerContextAggregator {
	private final Clock clock;
	private final ZoneId zoneId;
	private final int compactionTriggerTokens;
	private final PlannerVisionMode visionMode;

	private PlannerContextState state = PlannerContextState.initial();
	private PlannerContextSnapshot lastFrozenSnapshot;

	public PlannerContextAggregator(Clock clock, int compactionTriggerTokens, PlannerVisionMode visionMode) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.zoneId = clock.getZone();
		this.compactionTriggerTokens = compactionTriggerTokens;
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
	}

	public boolean compactionPending() {
		return state.compactionPending();
	}

	public boolean hasQueuedTriggers() {
		return !state.queuedTriggers().isEmpty();
	}

	public LlmUsageSnapshot lastObservedUsage() {
		return state.lastObservedUsage();
	}

	public PlannerContextDebugSnapshot debugSnapshot() {
		return new PlannerContextDebugSnapshot(
			compactionTriggerTokens,
			state.compactionPending(),
			state.rawArchiveTape().size(),
			state.canonicalTape().size(),
			state.pendingEntries().size(),
			lastFrozenSnapshot == null ? 0 : lastFrozenSnapshot.plannerConversation().messages().size(),
			state.queuedTriggers().size(),
			state.lastObservedEventSeqNo(),
			state.lastTimeBeaconAtMs(),
			state.lastObservedUsage(),
			state.lastAmbientContext(),
			state.activeCheckpoint()
		);
	}

	public void recordEvents(List<SemanticEvent> events, long anchorTimeMs) {
		if (events == null || events.isEmpty()) {
			return;
		}

		long previousSeqNo = state.lastObservedEventSeqNo();
		long latestSeqNo = previousSeqNo;
		ArrayList<PlannerContextEntry> notices = new ArrayList<>();
		boolean sawGap = false;
		for (SemanticEvent event : events) {
			if (event.seqNo() <= previousSeqNo) {
				continue;
			}
			if (!sawGap && event.seqNo() > previousSeqNo + 1L) {
				notices.add(new PlannerContextEntry(
					PlannerContextEntryType.NOTICE,
					null,
					"Some earlier context events were dropped before they could be summarized.",
					event.tick(),
					event.timestampMs()
				));
				sawGap = true;
			}
			String rendered = SemanticEventNoticeFormatter.format(event, anchorTimeMs);
			if (rendered != null && !rendered.isBlank()) {
				notices.add(new PlannerContextEntry(
					PlannerContextEntryType.NOTICE,
					null,
					rendered,
					event.tick(),
					event.timestampMs()
				));
			}
			latestSeqNo = Math.max(latestSeqNo, event.seqNo());
		}
		state = PlannerContextReducer.recordEntries(state, notices);
		state = PlannerContextReducer.updateObservedEventSeqNo(state, latestSeqNo);
	}

	public void enqueueTrigger(PlannerTrigger trigger) {
		Objects.requireNonNull(trigger, "trigger");
		state = PlannerContextReducer.enqueueTrigger(state, trigger.withSeqNo(state.nextTriggerSeqNo()));
	}

	public PlannerContextSnapshot freezePlannerSnapshot(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (state.queuedTriggers().isEmpty()) {
			return null;
		}

		long nowMs = request.timestampMs();
		if (PlannerContextPolicy.shouldInjectTimeBeacon(state.lastTimeBeaconAtMs(), nowMs)) {
			state = PlannerContextReducer.recordEntry(state, new PlannerContextEntry(
				PlannerContextEntryType.NOTICE,
				null,
				PlannerContextPolicy.timeBeaconText(nowMs, zoneId),
				-1L,
				nowMs
			));
			state = PlannerContextReducer.updateTimeBeacon(state, nowMs);
		}

		PlannerAmbientContext ambientContext = PlannerAmbientContext.fromRequest(request);
		state = PlannerContextReducer.recordEntries(
			state,
			PlannerAmbientContextRenderer.renderChanges(state.lastAmbientContext(), ambientContext, request.tick(), nowMs)
		);
		state = PlannerContextReducer.updateAmbientContext(state, ambientContext);
		state = PlannerContextReducer.commitPending(state, nowMs);

		PlannerTriggerBatch triggerBatch = PlannerTriggerBatch.of(state.queuedTriggers());
		PlannerRequest combinedRequest = request.withTriggerBatch(triggerBatch);
		PlannerContextSnapshot snapshot = new PlannerContextSnapshot(
			combinedRequest,
			triggerBatch,
			composeConversation(state.canonicalTape(), triggerBatch.toTerminalMessage())
		);
		lastFrozenSnapshot = snapshot;
		return snapshot;
	}

	public void commitAcceptedTriggerBatch(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		state = PlannerContextReducer.commitAcceptedTriggerBatch(
			state,
			snapshot.triggerBatch(),
			snapshot.request().tick(),
			snapshot.request().timestampMs()
		);
		lastFrozenSnapshot = null;
	}

	public void dropSupersededGeneration(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		if (lastFrozenSnapshot != null && lastFrozenSnapshot.equals(snapshot)) {
			lastFrozenSnapshot = null;
		}
	}

	public LlmConversation buildPlannerConversation(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.triggerBatch() != null) {
			for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
				enqueueTrigger(trigger);
			}
		}
		PlannerContextSnapshot snapshot = freezePlannerSnapshot(request);
		return snapshot == null ? composeConversation(state.canonicalTape(), null) : snapshot.plannerConversation();
	}

	public LlmConversation buildPlannerFollowUpConversation(PlannerContextSnapshot snapshot, String toolResult) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		return snapshot.plannerConversation().withAppended(
			LlmChatMessage.user("Tool result: " + (toolResult == null || toolResult.isBlank() ? "none" : toolResult), LlmMessageKind.TOOL_RESULT)
		);
	}

	public LlmConversation buildPlannerFollowUpConversation(PlannerContextSnapshot snapshot, String toolResult, LlmImageAttachment imageAttachment) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		return snapshot.plannerConversation().withAppended(
			LlmChatMessage.userWithImage(
				toolResult == null || toolResult.isBlank() ? "Tool result: image attached." : toolResult,
				LlmMessageKind.TOOL_RESULT,
				imageAttachment
			)
		);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, toolResult);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult, LlmImageAttachment imageAttachment) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, toolResult, imageAttachment);
	}

	public LlmConversation buildCompactionConversation() {
		long nowMs = clock.millis();
		state = PlannerContextReducer.commitPending(state, nowMs);
		return composeConversation(
			state.canonicalTape(),
			LlmChatMessage.user(PlannerPromptPolicy.compactionInstruction(), LlmMessageKind.TASK)
		);
	}

	public void recordAgentTurn(DialogueTurn turn) {
		Objects.requireNonNull(turn, "turn");
		state = PlannerContextReducer.recordEntry(state, new PlannerContextEntry(
			PlannerContextEntryType.ASSISTANT_TURN,
			turn.speaker(),
			turn.text(),
			turn.tick(),
			turn.timestampMs()
		));
	}

	public void recordUsage(LlmUsageSnapshot usage) {
		state = PlannerContextReducer.updateUsage(state, usage, compactionTriggerTokens);
	}

	public void recordObservedUsage(LlmUsageSnapshot usage) {
		state = PlannerContextReducer.updateObservedUsage(state, usage, state.compactionPending());
	}

	public void applyCheckpoint(CompactionCheckpoint checkpoint) {
		state = PlannerContextReducer.clearCompactionPending(state, checkpoint, clock.millis());
		lastFrozenSnapshot = null;
	}

	public void onCompactionFailure() {
		lastFrozenSnapshot = null;
	}

	public void clear() {
		state = PlannerContextState.initial();
		lastFrozenSnapshot = null;
	}

	private LlmConversation composeConversation(List<LlmChatMessage> canonicalTape, LlmChatMessage terminalMessage) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		messages.add(LlmChatMessage.system(PlannerPromptPolicy.systemPrompt(visionMode)));
		if (state.activeCheckpoint() != null) {
			messages.add(LlmChatMessage.user(state.activeCheckpoint().renderMessage(), LlmMessageKind.CHECKPOINT));
		}
		messages.addAll(canonicalTape);
		if (terminalMessage != null) {
			messages.add(terminalMessage);
		}
		return LlmConversation.of(messages);
	}
}
