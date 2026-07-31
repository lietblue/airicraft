package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record TaskFailure(TaskFailureCode code, String detail) {
	public TaskFailure {
		code = code == null ? TaskFailureCode.UNKNOWN : code;
		detail = Objects.requireNonNullElse(detail, "");
	}

	public static TaskFailure of(TaskFailureCode code, String detail) {
		return new TaskFailure(code, detail);
	}
}
