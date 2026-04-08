package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class MineBlocksVerification extends VerificationScenario {
	private final BooleanSupplier worldLoaded;
	private final BooleanSupplier actuationAllowed;
	private final Runnable injectMineGoalAction;
	private final Supplier<Boolean> mineGoalActive;
	private final BooleanSupplier taskRunning;

	public MineBlocksVerification(
		BooleanSupplier worldLoaded,
		BooleanSupplier actuationAllowed,
		Runnable injectMineGoalAction,
		Supplier<Boolean> mineGoalActive,
		BooleanSupplier taskRunning
	) {
		this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
		this.actuationAllowed = Objects.requireNonNull(actuationAllowed, "actuationAllowed");
		this.injectMineGoalAction = Objects.requireNonNull(injectMineGoalAction, "injectMineGoalAction");
		this.mineGoalActive = Objects.requireNonNull(mineGoalActive, "mineGoalActive");
		this.taskRunning = Objects.requireNonNull(taskRunning, "taskRunning");
	}

	@Override
	public String name() {
		return "mine_blocks.basic";
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("world loaded", worldLoaded)
			.require("actuation allowed", actuationAllowed)
			.action("inject mine goal", injectMineGoalAction)
			.waitUntil("mine goal active", 40, mineGoalActive::get)
			.waitUntil("task running", 40, taskRunning);
	}
}
