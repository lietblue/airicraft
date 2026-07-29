package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionGraphStartResult(
	ActionGraphAdmission admission,
	ActionGraphExecutionView execution,
	String foregroundExecutionId,
	int suspendedCount,
	int runnableCount,
	String failureCode,
	String message
) {
	public ActionGraphStartResult {
		admission = admission == null ? ActionGraphAdmission.BUSY : admission;
		foregroundExecutionId = foregroundExecutionId == null ? "" : foregroundExecutionId;
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
	}

	public Map<String, Object> toPayload(boolean verbose) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (execution != null) {
			payload.putAll(execution.toPayload(verbose));
		}
		payload.put("admission", admission.id());
		payload.put("foregroundExecutionId", foregroundExecutionId);
		payload.put("suspendedCount", suspendedCount);
		payload.put("runnableCount", runnableCount);
		if (!failureCode.isBlank()) {
			payload.put("failureCode", failureCode);
		}
		if (!message.isBlank()) {
			payload.put("message", message);
		}
		return payload;
	}
}
