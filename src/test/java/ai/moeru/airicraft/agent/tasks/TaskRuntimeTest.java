package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRuntimeTest {
	@Test
	void submitTaskCreatesQueuedSnapshot() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 8), 100L, "rin");

		TaskSnapshot snapshot = runtime.snapshot();
		assertEquals(TaskState.QUEUED, snapshot.state());
		assertEquals(TaskType.COLLECT_RESOURCE, snapshot.spec().type());
		assertEquals(TaskResourceKind.WOOD_LOGS, snapshot.spec().resourceKind());
		assertEquals(8, snapshot.spec().quantity());
		assertEquals(0, snapshot.progress().collected());
		assertEquals(8, snapshot.progress().remaining());
		assertEquals(TaskStep.NONE, snapshot.currentStep());
		assertEquals(TaskOwnership.NONE, snapshot.currentGoalOwnership());
		assertTrue(runtime.currentGoal().isEmpty());
	}

	@Test
	void cancelTaskTransitionsToCancelledAndClearsCurrentGoal() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 8), 100L, "rin");
		runtime.cancel(140L, "user_cancelled");

		assertEquals(TaskState.CANCELLED, runtime.snapshot().state());
		assertTrue(runtime.currentGoal().isEmpty());
		assertEquals(TaskOwnership.NONE, runtime.snapshot().currentGoalOwnership());
	}

	@Test
	void queuedCollectResourceTaskStartsPrimitiveMineGoalWhenTicked() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, true, 101L);

		assertEquals(TaskState.RUNNING, runtime.snapshot().state());
		assertEquals(TaskStep.MINE_TARGET, runtime.snapshot().currentStep());
		assertEquals(TaskOwnership.TASK_RUNTIME, runtime.snapshot().currentGoalOwnership());
		assertEquals(GoalType.MINE_BLOCKS, runtime.currentGoal().orElseThrow().type());
		assertEquals(4, runtime.currentGoal().orElseThrow().mineSpec().quantity());
	}

	@Test
	void collectResourceTaskCompletesWhenInventoryDeltaReachesTarget() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, true, 101L);
		runtime.tick(
			new TaskExecutionSnapshot(
				TaskExecutionState.RUNNING,
				runtime.currentGoal().orElseThrow(),
				null,
				null,
				null
			),
			4,
			true,
			true,
			102L
		);

		assertEquals(TaskState.COMPLETED, runtime.snapshot().state());
		assertEquals(4, runtime.snapshot().progress().collected());
		assertTrue(runtime.currentGoal().isEmpty());
	}

	@Test
	void pausedTaskStartsOnceActuationBecomesAllowed() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 0, false, true, 101L);

		assertEquals(TaskState.PAUSED_BY_SESSION_GATE, runtime.snapshot().state());
		assertTrue(runtime.currentGoal().isEmpty());

		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, true, 102L);

		assertEquals(TaskState.RUNNING, runtime.snapshot().state());
		assertEquals(GoalType.MINE_BLOCKS, runtime.currentGoal().orElseThrow().type());
	}

	@Test
	void queuedTaskCapturesBaselineBeforeCheckingCompletion() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 6), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 13, true, true, 101L);

		assertEquals(TaskState.RUNNING, runtime.snapshot().state());
		assertEquals(0, runtime.snapshot().progress().collected());
		assertEquals(6, runtime.snapshot().progress().remaining());
		assertEquals(19, runtime.currentGoal().orElseThrow().mineSpec().quantity());
	}

	@Test
	void cancelledMineWithPartialProgressWaitsForPickupThenRetriesRemainingQuantity() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 8), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, true, 101L);
		runtime.tick(
			new TaskExecutionSnapshot(
				TaskExecutionState.CANCELLED,
				runtime.currentGoal().orElseThrow(),
				null,
				"CANCELED",
				null
			),
			3,
			true,
			true,
			102L
		);

		assertEquals(TaskState.WAITING_FOR_PICKUP, runtime.snapshot().state());
		assertEquals(TaskStep.WAIT_FOR_PICKUP, runtime.snapshot().currentStep());
		assertTrue(runtime.currentGoal().isEmpty());

		runtime.tick(TaskExecutionSnapshot.idle(), 3, true, true, 110L);

		assertEquals(TaskState.WAITING_FOR_PICKUP, runtime.snapshot().state());
		assertTrue(runtime.currentGoal().isEmpty());

		runtime.tick(TaskExecutionSnapshot.idle(), 3, true, true, 123L);

		assertEquals(TaskState.RUNNING, runtime.snapshot().state());
		assertEquals(TaskStep.MINE_TARGET, runtime.snapshot().currentStep());
		assertEquals(8, runtime.currentGoal().orElseThrow().mineSpec().quantity());
	}

	@Test
	void waitingForPickupFailsWhenNoNearbyTargetsRemain() {
		TaskRuntime runtime = new TaskRuntime();

		runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 100L, "rin");
		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, true, 101L);
		runtime.tick(
			new TaskExecutionSnapshot(
				TaskExecutionState.CANCELLED,
				runtime.currentGoal().orElseThrow(),
				null,
				"CANCELED",
				null
			),
			0,
			true,
			false,
			102L
		);

		runtime.tick(TaskExecutionSnapshot.idle(), 0, true, false, 123L);

		assertEquals(TaskState.FAILED, runtime.snapshot().state());
		assertEquals("no_nearby_resource_targets", runtime.snapshot().lastFailure());
		assertTrue(runtime.currentGoal().isEmpty());
	}
}
