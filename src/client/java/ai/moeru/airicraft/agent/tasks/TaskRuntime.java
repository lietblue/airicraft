package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TaskRuntime {
	private final MissionRuntime missionRuntime;

	public TaskRuntime() {
		this(new MissionRuntime());
	}

	TaskRuntime(MissionRuntime missionRuntime) {
		this.missionRuntime = missionRuntime;
	}

	public void submit(TaskSpec spec, long tick, String source) {
		MissionSpec mission = new MissionSpec(
			"mission-" + UUID.randomUUID(),
			MissionType.COLLECT_RESOURCE,
			"Collect " + spec.quantity() + " " + spec.resourceKind().name().toLowerCase()
		);
		TaskLedger ledger = new TaskLedger(
			mission.missionId(),
			mission.missionType(),
			mission.goalText(),
			List.of(new LedgerStep(
				"collect_resource",
				LedgerStepKind.COLLECT_RESOURCE,
				new LedgerStepPayload(
					new CollectResourceStepArgs(spec.resourceKind(), spec.quantity(), "KEEP"),
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null
				),
				List.of(),
				LedgerStepStatus.ACTIVE,
				List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, spec.resourceKind(), spec.quantity(), null, null)),
				2,
				"Compatibility wrapper mission."
			)),
			"collect_resource",
			List.of(),
			"compatibility_submit",
			"Generated from legacy TaskSpec submit."
		);
		missionRuntime.submit(mission, ledger, tick, source);
	}

	public void applyPlannerLedger(TaskLedger ledger, long tick, String source) {
		missionRuntime.applyPlannerLedger(ledger, tick, source);
	}

	public void cancel(long tick, String reason) {
		missionRuntime.cancel(tick, reason);
	}

	public void clear() {
		missionRuntime.clear();
	}

	public TaskSnapshot snapshot() {
		return missionRuntime.taskSnapshot();
	}

	public MissionExecutionSnapshot executionSnapshot() {
		return missionRuntime.executionSnapshot();
	}

	public Optional<GoalSnapshot> currentGoal() {
		return missionRuntime.currentGoal();
	}

	public boolean hasActiveTask() {
		return missionRuntime.hasActiveTask();
	}

	public void tick(
		TaskExecutionSnapshot taskExecutionSnapshot,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		missionRuntime.tick(
			taskExecutionSnapshot,
			evidence,
			actuationAllowed,
			nearbyResourceTargetAvailable,
			tick
		);
	}

	public void tick(
		TaskExecutionSnapshot taskExecutionSnapshot,
		int currentResourceCount,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		TaskSpec currentSpec = snapshot().spec();
		missionRuntime.tick(
			taskExecutionSnapshot,
			new WorldEvidence(
				currentSpec == null ? java.util.Map.of() : java.util.Map.of(currentSpec.resourceKind(), currentResourceCount),
				java.util.Map.of(),
				nearbyResourceTargetAvailable ? java.util.Map.of("nearby", 1) : java.util.Map.of(),
				null,
				0,
				0,
				0,
				null,
				tick
			),
			actuationAllowed,
			nearbyResourceTargetAvailable,
			tick
		);
	}
}
