package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;
import ai.moeru.airicraft.agent.semantic.SemanticContextUpdateCoalescer;

import java.util.ArrayList;
import java.util.List;

final class PlannerContextReducer {
	private PlannerContextReducer() {
	}

	static PlannerContextState recordEntry(PlannerContextState state, PlannerContextEntry entry) {
		ArrayList<PlannerContextEntry> archive = new ArrayList<>(state.rawArchiveTape());
		archive.add(entry);
		ArrayList<PlannerContextEntry> pending = new ArrayList<>(state.pendingEntries());
		pending.add(entry);
		return new PlannerContextState(
			List.copyOf(archive),
			state.canonicalTape(),
			state.activeCheckpoint(),
			List.copyOf(pending),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState recordEntries(PlannerContextState state, List<PlannerContextEntry> entries) {
		PlannerContextState next = state;
		for (PlannerContextEntry entry : entries) {
			next = recordEntry(next, entry);
		}
		return next;
	}

	static PlannerContextState recordSemanticUpdates(PlannerContextState state, List<SemanticContextUpdate> updates, long anchorTimeMs) {
		if (updates == null || updates.isEmpty()) {
			return state;
		}

		ArrayList<PlannerContextEntry> archive = new ArrayList<>(state.rawArchiveTape());
		ArrayList<PlannerContextEntry> pending = new ArrayList<>(state.pendingEntries());
		for (SemanticContextUpdate update : updates) {
			if (update == null || update.text().isBlank()) {
				continue;
			}
			PlannerContextEntry entry = PlannerContextEntry.semanticNotice(update);
			archive.add(entry);
			boolean merged = false;
			for (int index = 0; index < pending.size(); index++) {
				PlannerContextEntry existingEntry = pending.get(index);
				SemanticContextUpdate existingUpdate = existingEntry.semanticUpdate();
				if (existingUpdate == null) {
					continue;
				}
				SemanticContextUpdate coalesced = SemanticContextUpdateCoalescer.mergeIfPossible(existingUpdate, update, anchorTimeMs);
				if (coalesced == null) {
					continue;
				}
				pending.set(index, PlannerContextEntry.semanticNotice(coalesced));
				merged = true;
				break;
			}
			if (!merged) {
				pending.add(entry);
			}
		}

		return new PlannerContextState(
			List.copyOf(archive),
			state.canonicalTape(),
			state.activeCheckpoint(),
			List.copyOf(pending),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState commitPending(PlannerContextState state, long anchorTimeMs) {
		if (state.pendingEntries().isEmpty()) {
			return state;
		}

		ArrayList<LlmChatMessage> canonical = new ArrayList<>(state.canonicalTape());
		for (PlannerContextEntry entry : state.pendingEntries()) {
			canonical.add(ContextMessageRenderer.renderEntry(entry, anchorTimeMs));
		}
		return new PlannerContextState(
			state.rawArchiveTape(),
			List.copyOf(canonical),
			state.activeCheckpoint(),
			List.of(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState updateTimeBeacon(PlannerContextState state, long timestampMs) {
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.canonicalTape(),
			state.activeCheckpoint(),
			state.pendingEntries(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			timestampMs,
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState updateObservedEventSeqNo(PlannerContextState state, long seqNo) {
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.canonicalTape(),
			state.activeCheckpoint(),
			state.pendingEntries(),
			Math.max(state.lastObservedEventSeqNo(), seqNo),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState updateAmbientContext(PlannerContextState state, PlannerAmbientContext ambientContext) {
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.canonicalTape(),
			state.activeCheckpoint(),
			state.pendingEntries(),
			state.lastObservedEventSeqNo(),
			ambientContext,
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState updateUsage(PlannerContextState state, LlmUsageSnapshot usage, int thresholdTokens) {
		boolean compactionPending = state.compactionPending() || PlannerContextPolicy.shouldCompact(usage, thresholdTokens);
		return updateObservedUsage(state, usage, compactionPending);
	}

	static PlannerContextState updateObservedUsage(PlannerContextState state, LlmUsageSnapshot usage, boolean compactionPending) {
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.canonicalTape(),
			state.activeCheckpoint(),
			state.pendingEntries(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			compactionPending,
			usage == null ? state.lastObservedUsage() : usage,
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState enqueueTrigger(PlannerContextState state, PlannerTrigger trigger) {
		ArrayList<PlannerTrigger> queued = new ArrayList<>(state.queuedTriggers());
		queued.add(trigger);
		return new PlannerContextState(
			state.rawArchiveTape(),
			state.canonicalTape(),
			state.activeCheckpoint(),
			state.pendingEntries(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			List.copyOf(queued),
			trigger.seqNo() + 1L
		);
	}

	static PlannerContextState commitAcceptedTriggerBatch(PlannerContextState state, PlannerTriggerBatch triggerBatch, long tick, long timestampMs) {
		if (triggerBatch == null || triggerBatch.isEmpty()) {
			return state;
		}

		ArrayList<PlannerContextEntry> archive = new ArrayList<>(state.rawArchiveTape());
		archive.add(new PlannerContextEntry(
			PlannerContextEntryType.USER_TURN,
			triggerBatch.primarySpeaker(),
			triggerBatch.renderPrompt(),
			tick,
			timestampMs
		));
		ArrayList<LlmChatMessage> canonical = new ArrayList<>(state.canonicalTape());
		canonical.add(triggerBatch.toTerminalMessage());
		ArrayList<PlannerTrigger> remainingQueued = new ArrayList<>();
		for (PlannerTrigger queuedTrigger : state.queuedTriggers()) {
			if (queuedTrigger.seqNo() > triggerBatch.endSeqNo()) {
				remainingQueued.add(queuedTrigger);
			}
		}
		return new PlannerContextState(
			List.copyOf(archive),
			List.copyOf(canonical),
			state.activeCheckpoint(),
			state.pendingEntries(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			state.lastTimeBeaconAtMs(),
			state.compactionPending(),
			state.lastObservedUsage(),
			List.copyOf(remainingQueued),
			state.nextTriggerSeqNo()
		);
	}

	static PlannerContextState clearCompactionPending(PlannerContextState state, CompactionCheckpoint checkpoint, long compactedAtMs) {
		ArrayList<LlmChatMessage> retained = new ArrayList<>();
		int retainedUserTurns = 0;
		for (int index = state.canonicalTape().size() - 1; index >= 0; index--) {
			LlmChatMessage message = state.canonicalTape().get(index);
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
			List.of(),
			state.lastObservedEventSeqNo(),
			state.lastAmbientContext(),
			compactedAtMs,
			false,
			state.lastObservedUsage(),
			state.queuedTriggers(),
			state.nextTriggerSeqNo()
		);
	}
}
