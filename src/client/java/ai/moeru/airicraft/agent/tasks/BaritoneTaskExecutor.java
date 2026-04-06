package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private final BaritoneFacade facade;

	private GoalSnapshot appliedGoal;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this.facade = Objects.requireNonNull(facade, "facade");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<GoalSnapshot> activeGoal) {
		if (!facade.isLoaded()) {
			appliedGoal = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				activeGoal.orElse(null),
				null,
				null,
				null
			);
			return Optional.empty();
		}

		if (activeGoal.isEmpty()) {
			if (appliedGoal != null) {
				facade.cancel();
				appliedGoal = null;
			}
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}

		if (!sameGoalTarget(activeGoal.get(), appliedGoal)) {
			if (appliedGoal != null) {
				facade.cancel();
			}
			applyGoal(activeGoal.get());
		}
		appliedGoal = activeGoal.get();

		Optional<String> pathEvent = facade.pollPathEvent();
		TaskExecutionState state = terminalStateFor(pathEvent)
			.orElseGet(() -> isTerminal(snapshot.state()) ? snapshot.state() : TaskExecutionState.RUNNING);
		snapshot = new TaskExecutionSnapshot(
			state,
			appliedGoal,
			facade.activeProcessName().orElse(null),
			pathEvent.orElse(null),
			facade.estimatedTicksToGoal().orElse(null)
		);

		return terminalStateFor(pathEvent).map(stateValue -> new TaskTerminalEvent(
			appliedGoal,
			stateValue,
			messageFor(stateValue)
		));
	}

	private void applyGoal(GoalSnapshot goal) {
		switch (goal.type()) {
			case FOLLOW_PLAYER -> facade.startFollow(goal.targetPlayer());
			case NAVIGATE_TO -> facade.startNavigate(goal.position());
			case MINE_BLOCKS -> facade.startMine(goal.mineSpec());
		}
	}

	private static Optional<TaskExecutionState> terminalStateFor(Optional<String> pathEvent) {
		if (pathEvent.isEmpty()) {
			return Optional.empty();
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "AT_GOAL" -> Optional.of(TaskExecutionState.COMPLETED);
			case "CALC_FAILED" -> Optional.of(TaskExecutionState.FAILED);
			case "CANCELLED", "CANCELED" -> Optional.of(TaskExecutionState.CANCELLED);
			default -> Optional.empty();
		};
	}

	private static boolean isTerminal(TaskExecutionState state) {
		return state == TaskExecutionState.COMPLETED
			|| state == TaskExecutionState.FAILED
			|| state == TaskExecutionState.CANCELLED;
	}

	private static boolean sameGoalTarget(GoalSnapshot left, GoalSnapshot right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.type() == right.type()
			&& Objects.equals(left.targetPlayer(), right.targetPlayer())
			&& Objects.equals(left.position(), right.position())
			&& Objects.equals(left.mineSpec(), right.mineSpec());
	}

	private static String messageFor(TaskExecutionState state) {
		return switch (state) {
			case COMPLETED -> "Goal reached";
			case FAILED -> "Path calculation failed";
			case CANCELLED -> "Task cancelled";
			default -> "Task update";
		};
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		facade.cancel();
		appliedGoal = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() {
		onWorldLeave();
	}
}
