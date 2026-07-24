package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.PlayerLifecycleState;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneTaskExecutorTest {
	@Test
	void navigateGoalStartsOnceAndReportsRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertEquals(1, facade.applySettingsCalls);
		assertEquals(1, facade.navigateCalls.size());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
	}

	@Test
	void deathGateCancelsActiveBaritoneProcess() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("nav-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		executor.tick(deadMultiplayer(), Optional.of(request));

		assertEquals(1, facade.cancelCalls);
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
	}

	@Test
	void terminalPathEventBecomesCompletedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("mine-task", goal)));
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("mine-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
	}

	@Test
	void activeMineProcessOwnsTargetCompletionFailureAndReselectionWithoutRestartingTask() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.mineProcessActive = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:short_grass"), 64),
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("mine-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> targetReached = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(targetReached.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("AT_GOAL", executor.snapshot().lastPathEvent());
		assertEquals(1, facade.mineCalls.size());

		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> calculationFailure = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(calculationFailure.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("CALC_FAILED", executor.snapshot().lastPathEvent());
		assertNull(executor.snapshot().terminationCause());
		assertEquals(1, facade.mineCalls.size());

		facade.pathEvents.add("CANCELED");
		Optional<TaskTerminalEvent> internalCancellation = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(internalCancellation.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals(1, facade.mineCalls.size());

		facade.mineProcessActive = false;
		facade.pathEvents.add("CANCELED");
		Optional<TaskTerminalEvent> exhausted = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(exhausted.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, exhausted.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.BARITONE_CANCELLED, exhausted.orElseThrow().terminationCause());
		assertEquals(1, facade.mineCalls.size());
	}

	@Test
	void calculationFailureAfterMineProcessExhaustionRemainsTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:short_grass"), 64),
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("mine-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> calculationFailure = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(calculationFailure.isPresent());
		assertEquals(TaskExecutionState.FAILED, calculationFailure.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.CALCULATION_FAILED, calculationFailure.orElseThrow().terminationCause());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
	}

	@Test
	void nonMiningCalculationFailureRemainsTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		WorldTaskRequest request = request("nav-task", goal);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> calculationFailure = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(calculationFailure.isPresent());
		assertEquals(TaskExecutionState.FAILED, calculationFailure.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.CALCULATION_FAILED, calculationFailure.orElseThrow().terminationCause());
	}

	@Test
	void mineTerminalSweepsPossibleDropFromTargetBlockBeforeCompleting() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		GoalPosition dropPosition = new GoalPosition(8, 64, 18, true);
		ArrayDeque<List<BaritoneTaskExecutor.MineDropTarget>> observedDrops = new ArrayDeque<>();
		observedDrops.add(List.of(new BaritoneTaskExecutor.MineDropTarget(7, dropPosition)));
		observedDrops.add(List.of());
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request ->
			MinedBlockDropMapper.matchingInventoryItemIds(request.goal().mineSpec().blockIds()).contains("minecraft:wheat_seeds")
				? observedDrops.removeFirst()
				: List.of()
		);
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 1), 20L, "planner_response");
		GoalPosition finalBrokenBlock = new GoalPosition(10, 64, 20, true);
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-task", goal, finalBrokenBlock);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("AT_GOAL");

		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		assertEquals(List.of(dropPosition), facade.navigateCalls);
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("pickup_sweep", executor.snapshot().lastPathEvent());
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(completed.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, completed.orElseThrow().terminalState());
	}

	@Test
	void cancelledMineBatchSweepsDropsFromMultipleBrokenTargetsBeforeReportingTerminalOutcome() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		GoalPosition firstBrokenBlock = new GoalPosition(10, 64, 20, true);
		GoalPosition secondBrokenBlock = new GoalPosition(18, 64, 20, true);
		GoalPosition firstDrop = new GoalPosition(10, 64, 21, true);
		GoalPosition secondDrop = new GoalPosition(18, 64, 19, true);
		ArrayDeque<List<BaritoneTaskExecutor.MineDropTarget>> observedDrops = new ArrayDeque<>();
		observedDrops.add(List.of(
			new BaritoneTaskExecutor.MineDropTarget(11, firstDrop),
			new BaritoneTaskExecutor.MineDropTarget(12, secondDrop)
		));
		observedDrops.add(List.of(new BaritoneTaskExecutor.MineDropTarget(12, secondDrop)));
		observedDrops.add(List.of());
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> observedDrops.removeFirst());
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 2), 20L, "planner_response");
		WorldTaskRequest request = WorldTaskRequest.collectMine(
			"mine-task",
			"mine-task",
			goal,
			List.of(firstBrokenBlock, secondBrokenBlock)
		);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CANCELED");
		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		assertEquals(List.of(firstDrop), facade.navigateCalls);

		facade.pathEvents.add("AT_GOAL");
		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		assertEquals(List.of(firstDrop, secondDrop), facade.navigateCalls);

		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> terminal = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(terminal.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, terminal.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.BARITONE_CANCELLED, terminal.orElseThrow().terminationCause());
	}

	@Test
	void minePickupFailsInsteadOfCompletingWhileMatchingDropRemains() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		GoalPosition dropPosition = new GoalPosition(10, 64, 20, true);
		BaritoneTaskExecutor.MineDropTarget drop = new BaritoneTaskExecutor.MineDropTarget(21, dropPosition);
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> List.of(drop));
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 1), 20L, "planner_response");
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-task", goal, dropPosition);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("AT_GOAL");
		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		facade.pathEvents.add("AT_GOAL");
		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> failed = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(failed.isPresent());
		assertEquals(TaskExecutionState.FAILED, failed.orElseThrow().terminalState());
		assertEquals("nearby_mined_drop_not_collected", failed.orElseThrow().message());
	}

	@Test
	void mineTerminalWithoutMatchingDropCompletesImmediately() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> List.of());
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:stone"), 1), 20L, "planner_response");
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-task", goal, new GoalPosition(10, 64, 20, true));

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(completed.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, completed.orElseThrow().terminalState());
		assertTrue(facade.navigateCalls.isEmpty());
	}

	@Test
	void satisfiedMineTreatsBaritoneCancellationAsCompletion() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> List.of());
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 14), 20L, "planner_response");
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-job", goal, new GoalPosition(10, 64, 20, true))
			.withMineGoalSatisfied(true);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CANCELED");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(completed.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, completed.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.GOAL_REACHED, completed.orElseThrow().terminationCause());
		assertEquals(TaskExecutionState.COMPLETED, executor.snapshot().state());
		assertEquals(TaskTerminationCause.GOAL_REACHED, executor.snapshot().terminationCause());
	}

	@Test
	void satisfiedMineCancellationSweepsMatchingDropBeforeCompleting() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 14), 20L, "planner_response");
		GoalPosition finalBrokenBlock = new GoalPosition(10, 64, 20, true);
		ArrayDeque<List<BaritoneTaskExecutor.MineDropTarget>> observedDrops = new ArrayDeque<>();
		observedDrops.add(List.of(new BaritoneTaskExecutor.MineDropTarget(31, finalBrokenBlock)));
		observedDrops.add(List.of());
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> observedDrops.removeFirst());
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-job", goal, finalBrokenBlock)
			.withMineGoalSatisfied(true);

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CANCELED");

		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		assertEquals(List.of(finalBrokenBlock), facade.navigateCalls);
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("pickup_sweep", executor.snapshot().lastPathEvent());

		facade.pathEvents.add("CANCELED");
		assertTrue(executor.tick(multiplayer(), Optional.of(request)).isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());

		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(completed.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, completed.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.GOAL_REACHED, completed.orElseThrow().terminationCause());
	}

	@Test
	void unsatisfiedMinePreservesBaritoneCancellation() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(() -> null, facade, request -> List.of());
		GoalSnapshot goal = new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, new GoalMineSpec(List.of("minecraft:short_grass"), 14), 20L, "planner_response");
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine-task", "mine-job", goal, new GoalPosition(10, 64, 20, true));

		executor.tick(multiplayer(), Optional.of(request));
		facade.pathEvents.add("CANCELED");
		Optional<TaskTerminalEvent> cancelled = executor.tick(multiplayer(), Optional.of(request));

		assertTrue(cancelled.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, cancelled.orElseThrow().terminalState());
		assertEquals(TaskTerminationCause.BARITONE_CANCELLED, cancelled.orElseThrow().terminationCause());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void cancelledPathEventVariantIsRecognizedAndSnapshotStaysTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("cancelled");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, event.orElseThrow().terminalState());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void cancelledNavigateAtReachedGoalCountsAsCompleted() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.navigationGoalReached = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(-7, 68, -4, true),
			null,
			20L,
			"verification"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
		assertEquals(TaskExecutionState.COMPLETED, executor.snapshot().state());
	}

	@Test
	void repeatedTerminalPathEventsOnlyEmitOneTerminalCallbackPerGoal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CANCELED");
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> first = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		Optional<TaskTerminalEvent> second = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void followRemainsRunningAfterReachingTarget() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("AT_GOAL", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice"), facade.followCalls);
	}

	@Test
	void cancelledFollowRearmsWithoutCompletingTask() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("FOLLOW_REACQUIRING", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice", "LanAlice"), facade.followCalls);
	}

	@Test
	void failedFollowCalculationRearmsWithoutCompletingTask() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("CALC_FAILED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("FOLLOW_REACQUIRING", executor.snapshot().lastPathEvent());
		assertEquals(List.of("LanAlice", "LanAlice"), facade.followCalls);
	}

	@Test
	void clearingFollowExplicitlyCancelsWithoutRearming() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		executor.tick(multiplayer(), Optional.empty());

		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
		assertEquals(1, facade.cancelCalls);
		assertEquals(List.of("LanAlice"), facade.followCalls);
	}

	@Test
	void sameGoalTargetWithDifferentTickDoesNotRestartPathing() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			21L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", first)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", second)));

		assertEquals(1, facade.navigateCalls.size());
	}

	@Test
	void sessionGatePausesWithoutCancellingGoalState() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"Alice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(singleplayerLocal(), Optional.of(request("follow-task", goal)));

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("follow-task", executor.snapshot().taskId());
		assertEquals(goal, executor.snapshot().activeGoal());
		assertEquals(0, facade.followCalls.size());
	}

	@Test
	void noGoalStaysIdleEvenWhenActuationIsBlocked() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);

		executor.tick(singleplayerLocal(), Optional.empty());

		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
	}

	@Test
	void replacingCollectMineTaskDoesNotSurfaceInternalCancelledEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 7),
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("job-1:mine:2", "job-1", second)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:mine:2", executor.snapshot().taskId());
		assertEquals(2, facade.mineCalls.size());
		assertEquals(1, facade.cancelCalls);
	}

	@Test
	void replacingMineTaskAfterCompletionResetsSnapshotToRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 7),
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		Optional<TaskTerminalEvent> replacement = executor.tick(multiplayer(), Optional.of(request("job-1:mine:2", "job-1", second)));

		assertTrue(completed.isPresent());
		assertTrue(replacement.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:mine:2", executor.snapshot().taskId());
		assertEquals(2, facade.mineCalls.size());
	}

	@Test
	void facadeStartFailureBecomesFailedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.startMineFailure = new IllegalArgumentException("Invalid block name minecraft:log");
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:log"), 8),
			20L,
			"planner_response"
		);

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("mine-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("Invalid block name minecraft:log", event.orElseThrow().message());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals("Invalid block name minecraft:log", executor.snapshot().lastPathEvent());
	}

	@Test
	void unavailableFacadeBecomesFailedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.loaded = false;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		Optional<TaskTerminalEvent> first = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		Optional<TaskTerminalEvent> second = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertEquals(TaskExecutionState.FAILED, first.orElseThrow().terminalState());
		assertEquals("baritone_unavailable", first.orElseThrow().message());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals("baritone_unavailable", executor.snapshot().lastPathEvent());
	}

	private static WorldTaskRequest request(String taskId, GoalSnapshot goal) {
		return WorldTaskRequest.direct(taskId, goal);
	}

	private static WorldTaskRequest request(String taskId, String sourceJobId, GoalSnapshot goal) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.MINE, goal);
	}

	private static SessionSnapshot multiplayer() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 30L);
	}

	private static SessionSnapshot singleplayerLocal() {
		return new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 30L);
	}

	private static SessionSnapshot deadMultiplayer() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			30L,
			PlayerLifecycleState.DEAD
		);
	}

	private static final class FakeBaritoneFacade implements BaritoneFacade {
		private int applySettingsCalls;
		private final List<GoalPosition> navigateCalls = new ArrayList<>();
		private final List<String> followCalls = new ArrayList<>();
		private final List<GoalMineSpec> mineCalls = new ArrayList<>();
		private final ArrayDeque<String> pathEvents = new ArrayDeque<>();
		private boolean navigationGoalReached;
		private boolean mineProcessActive;
		private int cancelCalls;
		private RuntimeException startMineFailure;
		private boolean loaded = true;

		@Override
		public boolean isLoaded() {
			return loaded;
		}

		@Override
		public void applySettings() {
			applySettingsCalls++;
		}

		@Override
		public void startFollow(String playerName) {
			followCalls.add(playerName);
		}

		@Override
		public void startNavigate(GoalPosition position) {
			navigateCalls.add(position);
		}

		@Override
		public void startNavigateNear(GoalPosition position, int radiusBlocks) {
			navigateCalls.add(position);
		}

		@Override
		public void startMine(GoalMineSpec spec) {
			if (startMineFailure != null) {
				throw startMineFailure;
			}
			mineCalls.add(spec);
		}

		@Override
		public boolean mineProcessActive() {
			return mineProcessActive;
		}

		@Override
		public void cancel() {
			cancelCalls++;
		}

		@Override
		public Optional<String> activeProcessName() {
			return Optional.of("fake");
		}

		@Override
		public Optional<Double> estimatedTicksToGoal() {
			return Optional.of(42.0D);
		}

		@Override
		public Optional<String> pollPathEvent() {
			return Optional.ofNullable(pathEvents.pollFirst());
		}

		@Override
		public boolean navigationGoalReached(GoalPosition position) {
			return navigationGoalReached;
		}
	}
}
