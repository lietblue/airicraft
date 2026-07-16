package ai.moeru.airicraft.agent.motor;

/** The action-graph and deterministic-task identity that owns one recurrent policy session. */
public record MotorGraphIdentity(
	String executionId,
	String graphActionId,
	String graphStepId,
	String graphPrimitive,
	int stepAttempt,
	String taskId,
	String taskType
) {
	public MotorGraphIdentity {
		executionId = required(executionId, "executionId");
		graphActionId = required(graphActionId, "graphActionId");
		graphStepId = required(graphStepId, "graphStepId");
		graphPrimitive = required(graphPrimitive, "graphPrimitive");
		if (stepAttempt < 0) {
			throw new IllegalArgumentException("stepAttempt must be non-negative");
		}
		taskId = required(taskId, "taskId");
		taskType = required(taskType, "taskType");
	}

	private static String required(String value, String field) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return normalized;
	}
}
