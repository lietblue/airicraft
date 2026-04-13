package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class NavigateVerification extends VerificationScenario {
	private final BooleanSupplier singleplayerLocal;
	private final Runnable openLanAction;
	private final BooleanSupplier lanHostActive;
	private final Runnable captureTargetAction;
	private final Runnable captureTaskBaselineAction;
	private final Runnable injectNavigateGoalAction;
	private final Supplier<Boolean> navigateGoalActive;
	private final BooleanSupplier taskRunning;
	private final BooleanSupplier taskCompleted;
	private final BooleanSupplier playerNearTarget;

	public NavigateVerification(
		BooleanSupplier singleplayerLocal,
		Runnable openLanAction,
		BooleanSupplier lanHostActive,
		Runnable captureTargetAction,
		Runnable captureTaskBaselineAction,
		Runnable injectNavigateGoalAction,
		Supplier<Boolean> navigateGoalActive,
		BooleanSupplier taskRunning,
		BooleanSupplier taskCompleted,
		BooleanSupplier playerNearTarget
	) {
		this.singleplayerLocal = Objects.requireNonNull(singleplayerLocal, "singleplayerLocal");
		this.openLanAction = Objects.requireNonNull(openLanAction, "openLanAction");
		this.lanHostActive = Objects.requireNonNull(lanHostActive, "lanHostActive");
		this.captureTargetAction = Objects.requireNonNull(captureTargetAction, "captureTargetAction");
		this.captureTaskBaselineAction = Objects.requireNonNull(captureTaskBaselineAction, "captureTaskBaselineAction");
		this.injectNavigateGoalAction = Objects.requireNonNull(injectNavigateGoalAction, "injectNavigateGoalAction");
		this.navigateGoalActive = Objects.requireNonNull(navigateGoalActive, "navigateGoalActive");
		this.taskRunning = Objects.requireNonNull(taskRunning, "taskRunning");
		this.taskCompleted = Objects.requireNonNull(taskCompleted, "taskCompleted");
		this.playerNearTarget = Objects.requireNonNull(playerNearTarget, "playerNearTarget");
	}

	@Override
	public String name() {
		return "navigate_to.basic";
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("in singleplayer local", singleplayerLocal)
			.action("open lan", openLanAction)
			.waitUntil("lan host active", 100, lanHostActive)
			.action("capture navigation target", captureTargetAction)
			.action("capture task baseline", captureTaskBaselineAction)
			.action("inject navigate goal", injectNavigateGoalAction)
			.waitUntil("navigate goal active", 100, navigateGoalActive::get)
			.waitUntil("task running", 100, taskRunning)
			.waitUntil("task completed", 400, taskCompleted)
			.assertThat("player near target", playerNearTarget);
	}
}
