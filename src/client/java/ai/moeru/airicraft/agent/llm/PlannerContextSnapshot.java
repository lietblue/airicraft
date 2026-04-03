package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerContextSnapshot(
	PlannerRequest request,
	PlannerTriggerBatch triggerBatch,
	LlmConversation plannerConversation,
	long includedSemanticEventSeqNoUpperBound,
	long includedSemanticGapVersion,
	PlannerAmbientContext renderedAmbientContext,
	long renderedTimeBeaconAtMs
) {
	public PlannerContextSnapshot {
		request = Objects.requireNonNull(request, "request");
		triggerBatch = Objects.requireNonNull(triggerBatch, "triggerBatch");
		plannerConversation = Objects.requireNonNull(plannerConversation, "plannerConversation");
		renderedAmbientContext = Objects.requireNonNull(renderedAmbientContext, "renderedAmbientContext");
	}
}
