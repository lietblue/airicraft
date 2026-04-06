package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record TaskSpec(TaskType type, TaskResourceKind resourceKind, int quantity) {
	public TaskSpec {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(resourceKind, "resourceKind");
	}
}
