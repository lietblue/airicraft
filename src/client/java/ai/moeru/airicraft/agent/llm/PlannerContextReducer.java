package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;

import java.util.ArrayList;
import java.util.List;

final class PlannerContextReducer {
	private PlannerContextReducer() {
	}

	static PlannerContextState recordObservedEvents(PlannerContextState state, SemanticEventQueryResult queryResult) {
		if (queryResult == null) {
			return state;
		}

		ArrayList<SemanticEvent> pending = new ArrayList<>(state.pendingSemanticEvents());
		for (SemanticEvent event : queryResult.events()) {
			if (event == null || event.seqNo() <= state.lastObservedEventSeqNo()) {
				continue;
			}
			pending.add(event);
		}

		long pendingSemanticGapVersion = state.pendingSemanticGapVersion();
		long nextSemanticGapVersion = state.nextSemanticGapVersion();
		if (queryResult.truncated()) {
			pendingSemanticGapVersion = nextSemanticGapVersion;
			nextSemanticGapVersion++;
		}

		return new PlannerContextState(
			state.rawArchiveTape(),
			state.acceptedConversationTape(),
			state.activeCheckpoint(),
			List.copyOf(pending),
			pendingSemanticGapVersion,
			nextSemanticGapVersion,
			Math.max(state.lastObservedEventSeqNo(), queryResult.latestSeqNo()),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState enqueueTrigger(PlannerContextState state, PlannerTrigger trigger) {
		ArrayList<PlannerTrigger> queued = new ArrayList<>(state.queuedTriggers());
		queued.add(trigger);
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.acceptedConversationTape(),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			List.copyOf(queued),
			trigger.seqNo() + 1L
		);
	}

	static PlannerContextState recordAcceptedUserTurn(
		PlannerContextState state,
		PlannerTriggerBatch triggerBatch,
		long tick,
		long timestampMs
	) {
		if (triggerBatch == null || triggerBatch.isEmpty()) {
			return state;
		}

		PlannerContextEntry archiveEntry = new PlannerContextEntry(
			PlannerContextEntryType.USER_TURN,
			triggerBatch.primarySpeaker(),
			triggerBatch.renderPrompt(),
			tick,
			timestampMs
		);
		ArrayList<PlannerContextEntry> archive = new ArrayList<>(state.rawArchiveTape());
		archive.add(archiveEntry);
		ArrayList<LlmChatMessage> accepted = new ArrayList<>(state.acceptedConversationTape());
		accepted.add(triggerBatch.toTerminalMessage());
		return new PlannerContextState(
			List.copyOf(archive),
			List.copyOf(accepted),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordAcceptedAssistantTurn(PlannerContextState state, DialogueTurn turn) {
		if (turn == null) {
			return state;
		}

		PlannerContextEntry archiveEntry = new PlannerContextEntry(
			PlannerContextEntryType.ASSISTANT_TURN,
			turn.speaker(),
			turn.text(),
			turn.tick(),
			turn.timestampMs()
		);
		ArrayList<PlannerContextEntry> archive = new ArrayList<>(state.rawArchiveTape());
		archive.add(archiveEntry);
		ArrayList<LlmChatMessage> accepted = new ArrayList<>(state.acceptedConversationTape());
		accepted.add(ContextMessageRenderer.renderEntry(archiveEntry, turn.timestampMs()));
		return new PlannerContextState(
			List.copyOf(archive),
			List.copyOf(accepted),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState commitAcceptedSnapshot(PlannerContextState state, PlannerContextSnapshot snapshot) {
		if (snapshot == null || snapshot.triggerBatch() == null || snapshot.triggerBatch().isEmpty()) {
			return state;
		}

		PlannerContextState next = recordAcceptedUserTurn(
			state,
			snapshot.triggerBatch(),
			snapshot.request().tick(),
			snapshot.request().timestampMs()
		);

		ArrayList<SemanticEvent> remainingPending = new ArrayList<>();
		for (SemanticEvent event : next.pendingSemanticEvents()) {
			if (event.seqNo() > snapshot.includedSemanticEventSeqNoUpperBound()) {
				remainingPending.add(event);
			}
		}

		ArrayList<PlannerTrigger> remainingQueued = new ArrayList<>();
		for (PlannerTrigger queuedTrigger : next.queuedTriggers()) {
			if (queuedTrigger.seqNo() > snapshot.triggerBatch().endSeqNo()) {
				remainingQueued.add(queuedTrigger);
			}
		}

		long pendingSemanticGapVersion = next.pendingSemanticGapVersion();
		if (
			snapshot.includedSemanticGapVersion() != 0L
			&& pendingSemanticGapVersion == snapshot.includedSemanticGapVersion()
		) {
			pendingSemanticGapVersion = 0L;
		}

		return new PlannerContextState(
			next.rawArchiveTape(),
			next.acceptedConversationTape(),
			next.activeCheckpoint(),
			List.copyOf(remainingPending),
			pendingSemanticGapVersion,
			next.nextSemanticGapVersion(),
			next.lastObservedEventSeqNo(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeBeaconAtMs() >= 0L ? snapshot.renderedTimeBeaconAtMs() : next.lastAcceptedTimeBeaconAtMs(),
			next.compactionPending(),
			next.lastObservedUsage(),
			List.copyOf(remainingQueued),
			next.nextTriggerSeqNo()
		);
	}

	static PlannerContextState updateUsage(PlannerContextState state, LlmUsageSnapshot usage, int thresholdTokens) {
		boolean compactionPending = state.compactionPending() || PlannerContextPolicy.shouldCompact(usage, thresholdTokens);
		return updateObservedUsage(state, usage, compactionPending);
	}

	static PlannerContextState updateObservedUsage(PlannerContextState state, LlmUsageSnapshot usage, boolean compactionPending) {
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.acceptedConversationTape(),
			state.activeCheckpoint(),
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			state.lastAcceptedTimeBeaconAtMs(),
			compactionPending,
			usage == null ? state.lastObservedUsage() : usage,
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState clearCompactionPending(PlannerContextState state, CompactionCheckpoint checkpoint, long compactedAtMs) {
		ArrayList<LlmChatMessage> retained = new ArrayList<>();
		int retainedUserTurns = 0;
		for (int index = state.acceptedConversationTape().size() - 1; index >= 0; index--) {
			LlmChatMessage message = state.acceptedConversationTape().get(index);
			if (message.kind() != LlmMessageKind.USER_TURN && message.kind() != LlmMessageKind.ASSISTANT_TURN) {
				continue;
			}
			retained.add(0, message);
			if (message.kind() == LlmMessageKind.USER_TURN) {
				retainedUserTurns++;
			}
			if (retainedUserTurns >= PlannerContextPolicy.RETAINED_USER_TURNS || retained.size() >= PlannerContextPolicy.RETAINED_MESSAGE_CAP) {
				break;
			}
		}

		return new PlannerContextState(
			state.rawArchiveTape(),
			List.copyOf(retained),
			checkpoint,
			state.pendingSemanticEvents(),
			state.pendingSemanticGapVersion(),
			state.nextSemanticGapVersion(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedAmbientContext(),
			compactedAtMs,
			false,
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}
}
