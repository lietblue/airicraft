package ai.moeru.airicraft.agent.dialogue;

import java.util.List;
import java.util.Objects;

public record DialogueTransition(
	DialogueState state,
	List<DialogueResponse> visibleResponses,
	List<DialogueEffect> effects
) {
	public DialogueTransition {
		state = Objects.requireNonNull(state, "state");
		visibleResponses = visibleResponses == null ? List.of() : List.copyOf(visibleResponses);
		effects = effects == null ? List.of() : List.copyOf(effects);
	}

	public DialogueResponse lastVisibleResponse() {
		return visibleResponses.isEmpty() ? null : visibleResponses.get(visibleResponses.size() - 1);
	}
}
