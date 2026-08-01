package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskFailureCode;

public final class ActionGraphFailurePolicy {
	private ActionGraphFailurePolicy() {
	}

	public static RecoveryCategory category(TaskFailureCode failureCode) {
		return switch (failureCode == null ? TaskFailureCode.UNKNOWN : failureCode) {
			case TRANSIENT, BUSY -> RecoveryCategory.RETRY;
			case MISSING_FACT, MISSING_ITEM -> RecoveryCategory.MISSING_FACT;
			case ENVIRONMENT_CHANGED -> RecoveryCategory.BLOCKED;
			case INVALID_ACTION -> RecoveryCategory.INVALID_REQUEST;
			case DESTRUCTIVE_DENIED, UNKNOWN, NONE -> RecoveryCategory.TERMINAL;
		};
	}

	enum RecoveryCategory {
		RETRY,
		MISSING_FACT,
		INVALID_REQUEST,
		BLOCKED,
		TERMINAL
	}
}
