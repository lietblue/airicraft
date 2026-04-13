package ai.moeru.airicraft.agent.llm;

public enum PlannerConversationDebugKind {
	SYSTEM,
	CHECKPOINT,
	NOTICE,
	USER_TURN,
	TOOL_RESULT,
	TASK,
	ASSISTANT_TURN,
	FAILURE;

	public static PlannerConversationDebugKind fromMessageKind(LlmMessageKind kind) {
		if (kind == null) {
			return NOTICE;
		}
		return switch (kind) {
			case SYSTEM -> SYSTEM;
			case CHECKPOINT -> CHECKPOINT;
			case NOTICE -> NOTICE;
			case USER_TURN -> USER_TURN;
			case TOOL_RESULT -> TOOL_RESULT;
			case TASK -> TASK;
			case ASSISTANT_TURN -> ASSISTANT_TURN;
		};
	}
}
