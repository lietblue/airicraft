package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.Objects;

public record BaritoneTaskRequest(
	String taskId,
	String sourceJobId,
	BaritoneTaskType type,
	GoalSnapshot goal
) {
	public BaritoneTaskRequest {
		taskId = normalizedValue(taskId, "taskId");
		sourceJobId = normalizedValue(sourceJobId, "sourceJobId");
		type = Objects.requireNonNull(type, "type");
		goal = Objects.requireNonNull(goal, "goal");
	}

	public static BaritoneTaskRequest direct(String taskId, GoalSnapshot goal) {
		return new BaritoneTaskRequest(taskId, taskId, typeFor(goal), goal);
	}

	public static BaritoneTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal) {
		return new BaritoneTaskRequest(taskId, sourceJobId, BaritoneTaskType.MINE, goal);
	}

	private static BaritoneTaskType typeFor(GoalSnapshot goal) {
		GoalType goalType = Objects.requireNonNull(goal, "goal").type();
		return switch (goalType) {
			case FOLLOW_PLAYER -> BaritoneTaskType.FOLLOW;
			case NAVIGATE_TO -> BaritoneTaskType.NAVIGATE;
			case MINE_BLOCKS -> BaritoneTaskType.MINE;
		};
	}

	private static String normalizedValue(String value, String fieldName) {
		String trimmed = value == null ? null : value.trim();
		if (trimmed == null || trimmed.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return trimmed;
	}
}
