package ai.moeru.airicraft.agent.observability;

import io.opentelemetry.api.trace.Span;

final class VendorAttributeAdapter {
	private final boolean weaveProfile;

	private VendorAttributeAdapter(boolean weaveProfile) {
		this.weaveProfile = weaveProfile;
	}

	static VendorAttributeAdapter forProfile(String profile) {
		return new VendorAttributeAdapter("weave".equalsIgnoreCase(profile));
	}

	boolean useThreadContextOnly() {
		return weaveProfile;
	}

	void onThreadId(Span span, String threadId) {
		if (weaveProfile && threadId != null && !threadId.isBlank()) {
			span.setAttribute("wandb.thread_id", threadId);
		}
	}

	void onTurn(Span span, boolean turn) {
		if (weaveProfile) {
			span.setAttribute("wandb.is_turn", turn);
		}
	}

	boolean exposeAsThreadRow(String spanName) {
		if (!weaveProfile) {
			return false;
		}
		return switch (spanName) {
			case AgentObservability.PLANNER_REQUEST_SPAN_NAME,
				AgentObservability.PLANNER_COMPACTION_SPAN_NAME,
				AgentObservability.FOLLOW_UP_SPAN_NAME,
				AgentObservability.VISION_DESCRIBE_SPAN_NAME -> true;
			default -> false;
		};
	}
}
