package ai.moeru.airicraft.agent.verification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VerificationRunner {
	private final Map<String, VerificationScenario> scenarios = new LinkedHashMap<>();
	private final List<VerificationStepResult> stepResults = new ArrayList<>();

	private VerificationScenario currentScenario;
	private int currentStepIndex;
	private int ticksOnCurrentStep;
	private VerificationReport report = VerificationReport.idle();

	public void register(VerificationScenario scenario) {
		scenarios.put(scenario.name(), scenario);
	}

	public List<String> scenarioNames() {
		return List.copyOf(scenarios.keySet());
	}

	public boolean start(String scenarioName) {
		VerificationScenario scenario = scenarios.get(scenarioName);
		if (scenario == null) {
			report = new VerificationReport(
				VerificationStatus.FAILED,
				scenarioName,
				"Unknown scenario: " + scenarioName,
				List.of(),
				Map.of("failureReason", "Unknown scenario: " + scenarioName)
			);
			return false;
		}

		scenario.onStart();
		currentScenario = scenario;
		currentStepIndex = 0;
		ticksOnCurrentStep = 0;
		stepResults.clear();
		report = new VerificationReport(VerificationStatus.RUNNING, scenario.name(), null, List.of(), diagnosticsFor(scenario, null));
		return true;
	}

	public void onTick() {
		if (currentScenario == null) {
			return;
		}

		List<VerificationStep> steps = currentScenario.steps();
		if (currentStepIndex >= steps.size()) {
			report = new VerificationReport(
				VerificationStatus.PASSED,
				currentScenario.name(),
				null,
				List.copyOf(stepResults),
				diagnosticsFor(currentScenario, null)
			);
			currentScenario = null;
			return;
		}

		VerificationStep step = steps.get(currentStepIndex);
		switch (step.type()) {
			case REQUIRE -> handleRequire(step);
			case ACTION -> handleAction(step);
			case WAIT_UNTIL -> handleWaitUntil(step);
			case ASSERT -> handleAssert(step);
		}
	}

	public VerificationReport report() {
		if (currentScenario == null) {
			return report;
		}
		return new VerificationReport(
			report.status(),
			report.scenarioName(),
			report.message(),
			report.steps(),
			diagnosticsFor(currentScenario, report.message())
		);
	}

	public void reset() {
		currentScenario = null;
		currentStepIndex = 0;
		ticksOnCurrentStep = 0;
		stepResults.clear();
		report = VerificationReport.idle();
	}

	private void handleRequire(VerificationStep step) {
		if (safePredicate(step)) {
			recordStep(step, VerificationStatus.PASSED, 0, null);
			advance();
			return;
		}

		fail(step, "Precondition not met");
	}

	private void handleAction(VerificationStep step) {
		try {
			if (step.action() != null) {
				step.action().run();
			}
			recordStep(step, VerificationStatus.PASSED, 0, null);
			advance();
		}
		catch (RuntimeException exception) {
			fail(step, exception.getMessage());
		}
	}

	private void handleWaitUntil(VerificationStep step) {
		if (safePredicate(step)) {
			recordStep(step, VerificationStatus.PASSED, ticksOnCurrentStep, null);
			advance();
			return;
		}

		if (ticksOnCurrentStep >= step.timeoutTicks()) {
			fail(step, "Timed out after " + step.timeoutTicks() + " ticks");
			return;
		}

		ticksOnCurrentStep++;
	}

	private void handleAssert(VerificationStep step) {
		if (safePredicate(step)) {
			recordStep(step, VerificationStatus.PASSED, 0, null);
			advance();
			return;
		}

		fail(step, "Assertion failed");
	}

	private boolean safePredicate(VerificationStep step) {
		return step.predicate() != null && step.predicate().getAsBoolean();
	}

	private void advance() {
		currentStepIndex++;
		ticksOnCurrentStep = 0;
	}

	private void fail(VerificationStep step, String message) {
		recordStep(step, VerificationStatus.FAILED, ticksOnCurrentStep, message);
		report = new VerificationReport(
			VerificationStatus.FAILED,
			currentScenario != null ? currentScenario.name() : null,
			message,
			List.copyOf(stepResults),
			diagnosticsFor(currentScenario, message)
		);
		currentScenario = null;
	}

	private void recordStep(VerificationStep step, VerificationStatus status, int waitedTicks, String message) {
		stepResults.add(new VerificationStepResult(step.description(), status, waitedTicks, message));
		if (currentScenario != null) {
			report = new VerificationReport(
				VerificationStatus.RUNNING,
				currentScenario.name(),
				null,
				List.copyOf(stepResults),
				diagnosticsFor(currentScenario, null)
			);
		}
	}

	private static Map<String, Object> diagnosticsFor(VerificationScenario scenario, String failureReason) {
		LinkedHashMap<String, Object> diagnostics = new LinkedHashMap<>();
		if (scenario != null) {
			diagnostics.putAll(scenario.diagnostics());
		}
		if (failureReason != null && !failureReason.isBlank()) {
			diagnostics.put("failureReason", failureReason);
		}
		return Map.copyOf(diagnostics);
	}
}
