package ai.moeru.airicraft.agent.dialogue;

import java.util.Locale;
import java.util.Optional;

public enum DialogueIntentType {
	SET_GOAL,
	CLEAR_GOAL,
	JOB_UPDATE,
	MISSION_UPDATE,
	SUBMIT_TASK,
	CANCEL_TASK,
	REPLY_ONLY,
	ASK_CLARIFICATION,
	ACKNOWLEDGE_FAILURE,
	NONE

	;

	public static Optional<DialogueIntentType> fromWire(String wireValue) {
		if (wireValue == null || wireValue.isBlank()) {
			return Optional.empty();
		}

		return switch (wireValue.toLowerCase(Locale.ROOT)) {
			case "set_goal" -> Optional.of(SET_GOAL);
			case "clear_goal" -> Optional.of(CLEAR_GOAL);
			case "job_update" -> Optional.of(JOB_UPDATE);
			case "mission_update" -> Optional.of(MISSION_UPDATE);
			case "submit_task" -> Optional.of(SUBMIT_TASK);
			case "cancel_task" -> Optional.of(CANCEL_TASK);
			case "reply_only" -> Optional.of(REPLY_ONLY);
			case "ask_clarification" -> Optional.of(ASK_CLARIFICATION);
			case "acknowledge_failure" -> Optional.of(ACKNOWLEDGE_FAILURE);
			case "none" -> Optional.of(NONE);
			default -> Optional.empty();
		};
	}
}
