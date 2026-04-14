package ai.moeru.airicraft.agent.dialogue;

import java.util.Map;
import java.util.Objects;

public sealed interface DialogueEffect permits DialogueEffect.AppendSemanticEvent {
	static AppendSemanticEvent appendSemanticEvent(String type, Map<String, Object> payload) {
		return new AppendSemanticEvent(type, payload);
	}

	record AppendSemanticEvent(String type, Map<String, Object> payload) implements DialogueEffect {
		public AppendSemanticEvent {
			type = Objects.requireNonNull(type, "type");
			payload = payload == null ? Map.of() : Map.copyOf(payload);
		}
	}
}
