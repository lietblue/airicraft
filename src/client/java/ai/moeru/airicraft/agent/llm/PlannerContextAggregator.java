package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.semantic.SemanticContextProjectionResult;
import ai.moeru.airicraft.agent.semantic.SemanticContextProjector;
import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;

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
	private final SemanticContextProjector semanticContextProjector = new SemanticContextProjector();

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

	public int queuedTriggerCount() {
		return state.queuedTriggers().size();
	}

	public LlmUsageSnapshot lastObservedUsage() {
		return state.lastObservedUsage();
	}

	public long lastObservedEventSeqNo() {
		return state.lastObservedEventSeqNo();
	}

	public PlannerContextDebugSnapshot debugSnapshot() {
		SemanticContextProjectionResult projection = pendingSemanticProjection(clock.millis());
		return new PlannerContextDebugSnapshot(
			compactionTriggerTokens,
			state.compactionPending(),
			state.rawArchiveTape().size(),
			state.acceptedConversationTape().size(),
			state.pendingSemanticEvents().size(),
			projection.updates().size(),
			lastFrozenSnapshot == null ? 0 : lastFrozenSnapshot.plannerConversation().messages().size(),
			state.queuedTriggers().size(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedTimeBeaconAtMs(),
			state.pendingSemanticGapVersion() != 0L,
			state.lastObservedUsage(),
			state.lastAcceptedAmbientContext(),
			state.activeCheckpoint()
		);
	}

	public void recordObservedEvents(SemanticEventQueryResult queryResult) {
		state = PlannerContextReducer.recordObservedEvents(state, queryResult);
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
		PlannerAmbientContext ambientContext = PlannerAmbientContext.fromRequest(request);
		long renderedTimeBeaconAtMs = PlannerContextPolicy.shouldInjectTimeBeacon(state.lastAcceptedTimeBeaconAtMs(), nowMs)
			? nowMs
			: -1L;
		List<LlmChatMessage> snapshotNotices = renderSnapshotNotices(request, ambientContext, renderedTimeBeaconAtMs);

		PlannerTriggerBatch triggerBatch = PlannerTriggerBatch.of(state.queuedTriggers());
		PlannerRequest combinedRequest = request.withTriggerBatch(triggerBatch);
		PlannerContextSnapshot snapshot = new PlannerContextSnapshot(
			combinedRequest,
			triggerBatch,
			composeConversation(state.acceptedConversationTape(), snapshotNotices, triggerBatch.toTerminalMessage()),
			state.pendingSemanticEvents().isEmpty() ? 0L : state.pendingSemanticEvents().getLast().seqNo(),
			state.pendingSemanticGapVersion(),
			ambientContext,
			renderedTimeBeaconAtMs
		);
		lastFrozenSnapshot = snapshot;
		return snapshot;
	}

	public void commitAcceptedTriggerBatch(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		state = PlannerContextReducer.commitAcceptedSnapshot(state, snapshot);
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
		return snapshot == null ? composeConversation(state.acceptedConversationTape(), List.of(), null) : snapshot.plannerConversation();
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
		return composeConversation(
			state.acceptedConversationTape(),
			List.of(),
			LlmChatMessage.user(PlannerPromptPolicy.compactionInstruction(), LlmMessageKind.TASK)
		);
	}

	public void recordAgentTurn(DialogueTurn turn) {
		Objects.requireNonNull(turn, "turn");
		state = PlannerContextReducer.recordAcceptedAssistantTurn(state, turn);
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

	private SemanticEventQueryResult pendingSemanticQueryResult() {
		List<SemanticEvent> events = state.pendingSemanticEvents();
		long oldestSeqNo = events.isEmpty() ? 0L : events.getFirst().seqNo();
		long latestSeqNo = events.isEmpty() ? state.lastObservedEventSeqNo() : events.getLast().seqNo();
		return new SemanticEventQueryResult(
			oldestSeqNo,
			latestSeqNo,
			state.pendingSemanticGapVersion() != 0L,
			List.copyOf(events)
		);
	}

	private SemanticContextProjectionResult pendingSemanticProjection(long anchorTimeMs) {
		return semanticContextProjector.project(pendingSemanticQueryResult(), anchorTimeMs);
	}

	private List<LlmChatMessage> renderSnapshotNotices(
		PlannerRequest request,
		PlannerAmbientContext ambientContext,
		long renderedTimeBeaconAtMs
	) {
		long anchorTimeMs = request.timestampMs();
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		if (renderedTimeBeaconAtMs >= 0L) {
			messages.add(ContextMessageRenderer.renderEntry(new PlannerContextEntry(
				PlannerContextEntryType.NOTICE,
				null,
				PlannerContextPolicy.timeBeaconText(renderedTimeBeaconAtMs, zoneId),
				-1L,
				renderedTimeBeaconAtMs
			), anchorTimeMs));
		}
		for (PlannerContextEntry entry : PlannerAmbientContextRenderer.renderChanges(
			state.lastAcceptedAmbientContext(),
			ambientContext,
			request.tick(),
			anchorTimeMs
		)) {
			messages.add(ContextMessageRenderer.renderEntry(entry, anchorTimeMs));
		}
		for (SemanticContextUpdate update : pendingSemanticProjection(anchorTimeMs).updates()) {
			messages.add(ContextMessageRenderer.renderEntry(PlannerContextEntry.semanticNotice(update), anchorTimeMs));
		}
		return List.copyOf(messages);
	}

	private LlmConversation composeConversation(
		List<LlmChatMessage> acceptedConversationTape,
		List<LlmChatMessage> snapshotNotices,
		LlmChatMessage terminalMessage
	) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		messages.add(LlmChatMessage.system(PlannerPromptPolicy.systemPrompt(visionMode)));
		if (state.activeCheckpoint() != null) {
			messages.add(LlmChatMessage.user(state.activeCheckpoint().renderMessage(), LlmMessageKind.CHECKPOINT));
		}
		messages.addAll(acceptedConversationTape);
		messages.addAll(snapshotNotices);
		if (terminalMessage != null) {
			messages.add(terminalMessage);
		}
		return LlmConversation.of(messages);
	}
}
