package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskFailureCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionGraphFailurePolicyTest {
	@Test
	void mapsEveryTypedFailureCodeToOneRecoveryCategory() {
		List<Map.Entry<TaskFailureCode, ActionGraphFailurePolicy.RecoveryCategory>> cases = List.of(
			Map.entry(TaskFailureCode.TRANSIENT, ActionGraphFailurePolicy.RecoveryCategory.RETRY),
			Map.entry(TaskFailureCode.BUSY, ActionGraphFailurePolicy.RecoveryCategory.RETRY),
			Map.entry(TaskFailureCode.MISSING_FACT, ActionGraphFailurePolicy.RecoveryCategory.MISSING_FACT),
			Map.entry(TaskFailureCode.MISSING_ITEM, ActionGraphFailurePolicy.RecoveryCategory.MISSING_FACT),
			Map.entry(TaskFailureCode.ENVIRONMENT_CHANGED, ActionGraphFailurePolicy.RecoveryCategory.BLOCKED),
			Map.entry(TaskFailureCode.INVALID_ACTION, ActionGraphFailurePolicy.RecoveryCategory.INVALID_REQUEST),
			Map.entry(TaskFailureCode.DESTRUCTIVE_DENIED, ActionGraphFailurePolicy.RecoveryCategory.TERMINAL),
			Map.entry(TaskFailureCode.UNKNOWN, ActionGraphFailurePolicy.RecoveryCategory.TERMINAL),
			Map.entry(TaskFailureCode.NONE, ActionGraphFailurePolicy.RecoveryCategory.TERMINAL)
		);

		for (Map.Entry<TaskFailureCode, ActionGraphFailurePolicy.RecoveryCategory> testCase : cases) {
			assertEquals(testCase.getValue(), ActionGraphFailurePolicy.category(testCase.getKey()), testCase.getKey()::name);
		}
	}
}
