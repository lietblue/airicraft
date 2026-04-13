package ai.moeru.airicraft.agent.verification;

import java.util.List;
import java.util.Map;

public abstract class VerificationScenario {
	private List<VerificationStep> cachedSteps;

	public abstract String name();

	protected void onStart() {
	}

	protected abstract void define(ScenarioBuilder builder);

	protected Map<String, Object> diagnostics() {
		return Map.of();
	}

	public final List<VerificationStep> steps() {
		if (cachedSteps == null) {
			ScenarioBuilder builder = new ScenarioBuilder();
			define(builder);
			cachedSteps = builder.build();
		}

		return cachedSteps;
	}
}
