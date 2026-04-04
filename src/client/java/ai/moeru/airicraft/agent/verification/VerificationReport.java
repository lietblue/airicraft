package ai.moeru.airicraft.agent.verification;

import java.util.List;
import java.util.Map;

public record VerificationReport(
	VerificationStatus status,
	String scenarioName,
	String message,
	List<VerificationStepResult> steps,
	Map<String, Object> diagnostics
) {
	public static VerificationReport idle() {
		return new VerificationReport(VerificationStatus.IDLE, null, null, List.of(), Map.of());
	}
}
