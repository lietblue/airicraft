package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentEventPipeline {
	@FunctionalInterface
	public interface TriggerFactory {
		PlannerTrigger create(SemanticEvent event, EventRoutingProfile profile);
	}

	private final SemanticEventBuffer rawEventBuffer;
	private final SemanticEventBuffer plannerEventBuffer;
	private final EventPolicyState policyState;
	private final Map<String, EventRoutingProfile> routingProfiles;
	private final AgentDebugRecorder debugRecorder;
	private long lastProcessedRawSeqNo;

	public AgentEventPipeline(
		SemanticEventBuffer rawEventBuffer,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles
	) {
		this(rawEventBuffer, plannerEventBuffer, policyState, routingProfiles, new AgentDebugRecorder());
	}

	public AgentEventPipeline(
		SemanticEventBuffer rawEventBuffer,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles,
		AgentDebugRecorder debugRecorder
	) {
		this.rawEventBuffer = Objects.requireNonNull(rawEventBuffer, "rawEventBuffer");
		this.plannerEventBuffer = Objects.requireNonNull(plannerEventBuffer, "plannerEventBuffer");
		this.policyState = Objects.requireNonNull(policyState, "policyState");
		this.routingProfiles = Map.copyOf(Objects.requireNonNull(routingProfiles, "routingProfiles"));
		this.debugRecorder = Objects.requireNonNull(debugRecorder, "debugRecorder");
	}

	public SemanticEvent appendRaw(long tick, String type, Map<String, Object> payload) {
		return rawEventBuffer.append(tick, type, payload);
	}

	public SemanticEventBuffer rawEventBuffer() {
		return rawEventBuffer;
	}

	public SemanticEventBuffer plannerEventBuffer() {
		return plannerEventBuffer;
	}

	public EventPolicyState policyState() {
		return policyState;
	}

	public void clear() {
		rawEventBuffer.clear();
		plannerEventBuffer.clear();
		policyState.clear();
		lastProcessedRawSeqNo = 0L;
		recordBufferState();
	}

	public void clearPlannerFeed() {
		plannerEventBuffer.clear();
		lastProcessedRawSeqNo = rawEventBuffer.latestSeqNo();
		recordBufferState();
	}

	public List<PlannerTrigger> drain(TriggerFactory triggerFactory) {
		Objects.requireNonNull(triggerFactory, "triggerFactory");
		ArrayList<PlannerTrigger> triggers = new ArrayList<>();
		while (true) {
			SemanticEventQueryResult queryResult = rawEventBuffer.query(lastProcessedRawSeqNo <= 0L ? null : lastProcessedRawSeqNo);
			if (queryResult.events().isEmpty()) {
				return List.copyOf(triggers);
			}
			for (SemanticEvent event : queryResult.events()) {
				lastProcessedRawSeqNo = event.seqNo();
				triggers.addAll(route(event, triggerFactory));
			}
		}
	}

	private List<PlannerTrigger> route(SemanticEvent event, TriggerFactory triggerFactory) {
		EventRoutingProfile profile = routingProfiles.getOrDefault(event.type(), EventRoutingProfile.rawOnly(event.type()));
		if (!profile.semanticEligible() && !profile.triggerEligible()) {
			recordBufferState();
			return List.of();
		}

		EventPolicyDecision decision = policyState.evaluate(event, profile.policyBypass());
		if (decision.intervened()) {
			EventPolicyIntervention intervention = new EventPolicyIntervention(
				event.seqNo(),
				event.type(),
				decision.effect(),
				decision.matchedRuleId(),
				decision.reason(),
				event.timestampMs()
			);
			policyState.recordIntervention(intervention);
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("sourceEventSeqNo", event.seqNo());
			payload.put("sourceEventType", event.type());
			payload.put("effect", decision.effect().name());
			if (decision.matchedRuleId() != null) {
				payload.put("matchedRuleId", decision.matchedRuleId());
			}
			if (decision.reason() != null) {
				payload.put("reason", decision.reason());
			}
			rawEventBuffer.append(event.tick(), event.timestampMs(), "policy.event_intervened", payload);
		}

		boolean emitSemantic = profile.semanticEligible();
		boolean emitTrigger = profile.triggerEligible();
		switch (decision.effect()) {
			case IGNORE -> {
				emitSemantic = false;
				emitTrigger = false;
			}
			case SEMANTIC_ONLY -> emitTrigger = false;
			case TRIGGER_ONLY -> emitSemantic = false;
			case ALLOW -> {
			}
		}

		if (emitSemantic) {
			plannerEventBuffer.append(event.tick(), event.timestampMs(), event.type(), event.payload());
		}

		PlannerTrigger trigger = emitTrigger ? triggerFactory.create(event, profile) : null;
		debugRecorder.recordEventRouting(
			event.tick(),
			event.timestampMs(),
			event.seqNo(),
			event.type(),
			decision.effect().name(),
			emitSemantic,
			trigger != null,
			plannerEventBuffer.latestSeqNo(),
			trigger == null || trigger.type() == null ? null : trigger.type().name()
		);
		recordBufferState();
		if (trigger == null) {
			return List.of();
		}
		return List.of(trigger);
	}

	private void recordBufferState() {
		debugRecorder.updateEventPipelineBufferState(
			rawEventBuffer.latestSeqNo(),
			plannerEventBuffer.latestSeqNo(),
			rawEventBuffer.droppedCount(),
			plannerEventBuffer.droppedCount(),
			lastProcessedRawSeqNo
		);
	}
}
