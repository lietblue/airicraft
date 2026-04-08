package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MissionRuntime {
	private final StepExecutorRegistry stepExecutorRegistry;

	private MissionSpec mission;
	private TaskLedger ledger;
	private TaskSnapshot taskSnapshot = TaskSnapshot.idle();
	private MissionExecutionSnapshot executionSnapshot = MissionExecutionSnapshot.idle();
	private StepExecutor activeExecutor;
	private String activeExecutorStepId;
	private final Map<String, LedgerStepStatus> terminalStatuses = new HashMap<>();

	public MissionRuntime() {
		this(new StepExecutorRegistry());
	}

	MissionRuntime(StepExecutorRegistry stepExecutorRegistry) {
		this.stepExecutorRegistry = Objects.requireNonNull(stepExecutorRegistry, "stepExecutorRegistry");
	}

	public void submit(MissionSpec mission, TaskLedger seedLedger, long tick, String source) {
		Objects.requireNonNull(mission, "mission");
		Objects.requireNonNull(seedLedger, "seedLedger");
		this.mission = mission;
		terminalStatuses.clear();
		acceptLedger(normalizeLedger(seedLedger), tick, source);
	}

	public void applyPlannerLedger(TaskLedger nextLedger, long tick, String source) {
		Objects.requireNonNull(nextLedger, "nextLedger");
		TaskLedger normalizedLedger = normalizeLedger(nextLedger);
		if (ledger == null || !Objects.equals(ledger.missionId(), normalizedLedger.missionId())) {
			terminalStatuses.clear();
			if (!validateLedgerTransition(normalizedLedger)) {
				taskSnapshot = new TaskSnapshot(
					TaskState.FAILED,
					missionFromLedger(normalizedLedger),
					normalizedLedger,
					legacySpecFromLedger(normalizedLedger),
					taskSnapshot.progress(),
					TaskStep.NONE,
					TaskOwnership.NONE,
					source,
					"illegal_ledger_transition",
					normalizedLedger.activeStepId(),
					activeStepKind(normalizedLedger),
					new StepExecutionResult(
						normalizedLedger.activeStepId(),
						StepExecutionStatus.FAILED,
						"illegal_ledger_transition",
						Map.of(),
						Map.of(),
						tick
					),
					tick
				);
				executionSnapshot = new MissionExecutionSnapshot(
					missionFromLedger(normalizedLedger),
					normalizedLedger,
					activeStep(normalizedLedger).orElse(null),
					executionSnapshot.evidence(),
					taskSnapshot.lastStepResult(),
					executionSnapshot.primitiveExecution()
				);
				cancelActiveExecutor("illegal_ledger_transition", tick);
				return;
			}
			acceptLedger(normalizedLedger, tick, source);
			return;
		}
		if (!validateLedgerTransition(normalizedLedger)) {
			taskSnapshot = new TaskSnapshot(
				TaskState.FAILED,
				missionFromLedger(normalizedLedger),
				normalizedLedger,
				legacySpecFromLedger(normalizedLedger),
				taskSnapshot.progress(),
				TaskStep.NONE,
				TaskOwnership.NONE,
				source,
				"illegal_ledger_transition",
				normalizedLedger.activeStepId(),
				activeStepKind(normalizedLedger),
				executionSnapshot.lastStepResult(),
				tick
			);
			executionSnapshot = new MissionExecutionSnapshot(
				missionFromLedger(normalizedLedger),
				normalizedLedger,
				activeStep(normalizedLedger).orElse(null),
				executionSnapshot.evidence(),
				new StepExecutionResult(
					normalizedLedger.activeStepId(),
					StepExecutionStatus.FAILED,
					"illegal_ledger_transition",
					Map.of(),
					Map.of(),
					tick
				),
				executionSnapshot.primitiveExecution()
			);
			cancelActiveExecutor("illegal_ledger_transition", tick);
			return;
		}
		acceptLedger(normalizedLedger, tick, source);
	}

	public void cancel(long tick, String reason) {
		if (taskSnapshot.state() == TaskState.IDLE) {
			return;
		}
		cancelActiveExecutor(reason, tick);
		taskSnapshot = new TaskSnapshot(
			TaskState.CANCELLED,
			mission,
			ledger,
			taskSnapshot.spec(),
			taskSnapshot.progress(),
			TaskStep.NONE,
			TaskOwnership.NONE,
			taskSnapshot.source(),
			reason,
			ledger == null ? null : ledger.activeStepId(),
			activeStepKind(ledger),
			new StepExecutionResult(ledger == null ? null : ledger.activeStepId(), StepExecutionStatus.CANCELLED, reason, Map.of(), Map.of(), tick),
			tick
		);
	}

	public void clear() {
		cancelActiveExecutor("cleared", -1L);
		mission = null;
		ledger = null;
		taskSnapshot = TaskSnapshot.idle();
		executionSnapshot = MissionExecutionSnapshot.idle();
		terminalStatuses.clear();
	}

	public TaskSnapshot taskSnapshot() {
		return taskSnapshot;
	}

	public MissionExecutionSnapshot executionSnapshot() {
		return executionSnapshot;
	}

	public Optional<GoalSnapshot> currentGoal() {
		return activeExecutor == null ? Optional.empty() : Optional.ofNullable(activeExecutor.snapshot().currentGoal());
	}

	public boolean hasActiveTask() {
		return switch (taskSnapshot.state()) {
			case IDLE, FAILED, CANCELLED, COMPLETED -> false;
			default -> true;
		};
	}

	public void tick(
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyStepTargetsAvailable,
		long tick
	) {
		if (ledger == null || mission == null) {
			executionSnapshot = MissionExecutionSnapshot.idle();
			return;
		}

		LedgerStep activeStep = activeStep(ledger).orElse(null);
		if (completionSatisfied(ledger.completionCriteria(), evidence)) {
			TaskLedger completedLedger = updateStepStatus(ledger, ledger.activeStepId(), LedgerStepStatus.COMPLETED);
			ledger = completedLedger;
			rememberTerminalStatuses(completedLedger);
			LedgerStep finishStep = completedLedger.steps().stream()
				.filter(step -> step.kind() == LedgerStepKind.FINISH)
				.findFirst()
				.orElse(activeStep);
			cancelActiveExecutor("mission_completed", tick);
			taskSnapshot = new TaskSnapshot(
				TaskState.COMPLETED,
				mission,
				completedLedger,
				legacySpecFromLedger(completedLedger),
				taskSnapshot.progress(),
				TaskStep.NONE,
				TaskOwnership.NONE,
				taskSnapshot.source(),
				null,
				findFinishStepId(completedLedger),
				LedgerStepKind.FINISH,
				new StepExecutionResult(
					completedLedger.activeStepId(),
					StepExecutionStatus.COMPLETED,
					null,
					Map.of(),
					Map.of("reason", "completion_criteria_satisfied"),
					tick
				),
				tick
			);
			executionSnapshot = new MissionExecutionSnapshot(mission, completedLedger, finishStep, evidence, taskSnapshot.lastStepResult(), primitiveExecution);
			return;
		}

		if (activeStep == null) {
			if (
				executionSnapshot.lastStepResult().status() == StepExecutionStatus.FAILED
					|| executionSnapshot.lastStepResult().status() == StepExecutionStatus.CANCELLED
					|| executionSnapshot.lastStepResult().status() == StepExecutionStatus.COMPLETED
			) {
				executionSnapshot = new MissionExecutionSnapshot(mission, ledger, null, evidence, executionSnapshot.lastStepResult(), primitiveExecution);
				return;
			}
			taskSnapshot = new TaskSnapshot(
				TaskState.QUEUED,
				mission,
				ledger,
				legacySpecFromLedger(ledger),
				new TaskProgressSnapshot(0, 0),
				TaskStep.NONE,
				TaskOwnership.NONE,
				taskSnapshot.source(),
				null,
				null,
				null,
				executionSnapshot.lastStepResult(),
				tick
			);
			executionSnapshot = new MissionExecutionSnapshot(mission, ledger, null, evidence, executionSnapshot.lastStepResult(), primitiveExecution);
			return;
		}

		if (!Objects.equals(activeExecutorStepId, activeStep.id())) {
			cancelActiveExecutor("step_changed", tick);
			activeExecutor = stepExecutorRegistry.executorFor(activeStep);
			activeExecutor.begin(activeStep, evidence, tick);
			activeExecutorStepId = activeStep.id();
		}

		StepExecutorTickResult tickResult = activeExecutor.tick(activeStep, evidence, primitiveExecution, actuationAllowed, nearbyStepTargetsAvailable, tick);
		StepExecutionResult lastStepResult = tickResult.stepResult();
		if (lastStepResult.status() == StepExecutionStatus.COMPLETED) {
			ledger = updateStepStatus(ledger, activeStep.id(), LedgerStepStatus.COMPLETED);
			rememberTerminalStatuses(ledger);
		}
		else if (lastStepResult.status() == StepExecutionStatus.FAILED) {
			ledger = updateStepStatus(ledger, activeStep.id(), LedgerStepStatus.FAILED);
			rememberTerminalStatuses(ledger);
		}

		taskSnapshot = new TaskSnapshot(
			tickResult.taskState(),
			mission,
			ledger,
			legacySpecFromLedger(ledger),
			tickResult.progress(),
			tickResult.taskStep(),
			tickResult.ownership(),
			taskSnapshot.source(),
			lastStepResult.failureReason(),
			ledger.activeStepId(),
			activeStep.kind(),
			lastStepResult,
			tick
		);
		executionSnapshot = new MissionExecutionSnapshot(mission, ledger, activeStep, evidence, lastStepResult, primitiveExecution);
		if (tickResult.taskState() == TaskState.FAILED || tickResult.taskState() == TaskState.COMPLETED) {
			cancelActiveExecutor(lastStepResult.failureReason(), tick);
		}
	}

	private void acceptLedger(TaskLedger nextLedger, long tick, String source) {
		cancelActiveExecutor("ledger_replaced", tick);
		ledger = nextLedger;
		mission = missionFromLedger(nextLedger);
		TaskSpec legacySpec = legacySpecFromLedger(nextLedger);
		rememberTerminalStatuses(nextLedger);
		taskSnapshot = new TaskSnapshot(
			TaskState.QUEUED,
			mission,
			nextLedger,
			legacySpec,
			legacySpec == null ? new TaskProgressSnapshot(0, 0) : TaskProgressSnapshot.of(0, legacySpec.quantity()),
			TaskStep.NONE,
			TaskOwnership.NONE,
			source,
			null,
			nextLedger.activeStepId(),
			activeStepKind(nextLedger),
			executionSnapshot.lastStepResult(),
			tick
		);
		executionSnapshot = new MissionExecutionSnapshot(mission, nextLedger, activeStep(nextLedger).orElse(null), executionSnapshot.evidence(), executionSnapshot.lastStepResult(), executionSnapshot.primitiveExecution());
	}

	private void cancelActiveExecutor(String reason, long tick) {
		if (activeExecutor != null) {
			activeExecutor.cancel(reason, tick);
		}
		activeExecutor = null;
		activeExecutorStepId = null;
	}

	private boolean validateLedgerTransition(TaskLedger nextLedger) {
		if (nextLedger.steps().stream().map(LedgerStep::id).distinct().count() != nextLedger.steps().size()) {
			return false;
		}
		if (nextLedger.activeStepId() != null && nextLedger.steps().stream().noneMatch(step -> step.id().equals(nextLedger.activeStepId()))) {
			return false;
		}
		long activeCount = nextLedger.steps().stream().filter(step -> step.status() == LedgerStepStatus.ACTIVE).count();
		if (nextLedger.activeStepId() == null) {
			if (activeCount > 0L) {
				return false;
			}
		}
		else if (activeCount != 1L) {
			return false;
		}
		else if (nextLedger.steps().stream().noneMatch(step -> step.id().equals(nextLedger.activeStepId()) && step.status() == LedgerStepStatus.ACTIVE)) {
			return false;
		}
		Map<String, LedgerStep> byId = new HashMap<>();
		for (LedgerStep step : nextLedger.steps()) {
			byId.put(step.id(), step);
		}
		for (LedgerStep step : nextLedger.steps()) {
			for (String dependsOn : step.dependsOn()) {
				LedgerStep dependency = byId.get(dependsOn);
				if (dependency == null) {
					return false;
				}
				if (step.status() == LedgerStepStatus.ACTIVE && dependency.status() != LedgerStepStatus.COMPLETED) {
					return false;
				}
			}
			LedgerStepStatus previousTerminal = terminalStatuses.get(step.id());
			if ((previousTerminal == LedgerStepStatus.COMPLETED || previousTerminal == LedgerStepStatus.CANCELLED)
				&& step.status() != previousTerminal) {
				return false;
			}
		}
		return !hasDependencyCycle(nextLedger.steps());
	}

	private static TaskLedger normalizeLedger(TaskLedger ledger) {
		if (ledger == null) {
			return null;
		}
		Map<String, LedgerStep> byId = new HashMap<>();
		for (LedgerStep step : ledger.steps()) {
			byId.put(step.id(), step);
		}
		List<LedgerStep> normalizedSteps = ledger.steps();
		String normalizedActiveStepId = ledger.activeStepId();
		long activeCount = ledger.steps().stream().filter(step -> step.status() == LedgerStepStatus.ACTIVE).count();

		if (normalizedActiveStepId != null) {
			LedgerStep designatedStep = byId.get(normalizedActiveStepId);
			if (
				designatedStep != null
					&& designatedStep.status() == LedgerStepStatus.PENDING
					&& activeCount == 0L
					&& dependenciesSatisfied(designatedStep, byId)
			) {
				normalizedSteps = activateStep(ledger.steps(), designatedStep.id());
				activeCount = 1L;
			}
		}
		else if (activeCount == 1L) {
			normalizedActiveStepId = ledger.steps().stream()
				.filter(step -> step.status() == LedgerStepStatus.ACTIVE)
				.map(LedgerStep::id)
				.findFirst()
				.orElse(null);
		}

		if (normalizedActiveStepId == null && activeCount == 0L) {
			LedgerStep nextExecutableStep = ledger.steps().stream()
				.filter(step -> step.status() == LedgerStepStatus.PENDING)
				.filter(step -> dependenciesSatisfied(step, byId))
				.findFirst()
				.orElse(null);
			if (nextExecutableStep != null) {
				normalizedActiveStepId = nextExecutableStep.id();
				normalizedSteps = activateStep(ledger.steps(), nextExecutableStep.id());
			}
		}

		if (normalizedSteps == ledger.steps() && Objects.equals(normalizedActiveStepId, ledger.activeStepId())) {
			return ledger;
		}
		return new TaskLedger(
			ledger.missionId(),
			ledger.missionType(),
			ledger.goalText(),
			normalizedSteps,
			normalizedActiveStepId,
			ledger.completionCriteria(),
			ledger.replanReason(),
			ledger.plannerNotes()
		);
	}

	private static List<LedgerStep> activateStep(List<LedgerStep> steps, String stepId) {
		return steps.stream()
			.map(step -> step.id().equals(stepId)
				? new LedgerStep(step.id(), step.kind(), step.args(), step.dependsOn(), LedgerStepStatus.ACTIVE, step.expectedEvidence(), step.retryBudget(), step.notes())
				: step)
			.toList();
	}

	private static boolean dependenciesSatisfied(LedgerStep step, Map<String, LedgerStep> byId) {
		for (String dependsOn : step.dependsOn()) {
			LedgerStep dependency = byId.get(dependsOn);
			if (dependency == null || dependency.status() != LedgerStepStatus.COMPLETED) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasDependencyCycle(List<LedgerStep> steps) {
		Map<String, LedgerStep> byId = new HashMap<>();
		for (LedgerStep step : steps) {
			byId.put(step.id(), step);
		}
		Set<String> visiting = new HashSet<>();
		Set<String> visited = new HashSet<>();
		for (LedgerStep step : steps) {
			if (hasDependencyCycle(step.id(), byId, visiting, visited)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasDependencyCycle(
		String stepId,
		Map<String, LedgerStep> byId,
		Set<String> visiting,
		Set<String> visited
	) {
		if (visited.contains(stepId)) {
			return false;
		}
		if (!visiting.add(stepId)) {
			return true;
		}
		LedgerStep step = byId.get(stepId);
		if (step != null) {
			for (String dependency : step.dependsOn()) {
				if (hasDependencyCycle(dependency, byId, visiting, visited)) {
					return true;
				}
			}
		}
		visiting.remove(stepId);
		visited.add(stepId);
		return false;
	}

	private void rememberTerminalStatuses(TaskLedger ledger) {
		for (LedgerStep step : ledger.steps()) {
			if (step.status() == LedgerStepStatus.COMPLETED || step.status() == LedgerStepStatus.CANCELLED) {
				terminalStatuses.put(step.id(), step.status());
			}
		}
	}

	private static MissionSpec missionFromLedger(TaskLedger ledger) {
		return new MissionSpec(ledger.missionId(), ledger.missionType(), ledger.goalText());
	}

	private static Optional<LedgerStep> activeStep(TaskLedger ledger) {
		if (ledger == null || ledger.activeStepId() == null) {
			return Optional.empty();
		}
		return ledger.steps().stream()
			.filter(step -> step.id().equals(ledger.activeStepId()) && step.status() == LedgerStepStatus.ACTIVE)
			.findFirst();
	}

	private static LedgerStepKind activeStepKind(TaskLedger ledger) {
		return activeStep(ledger).map(LedgerStep::kind).orElse(null);
	}

	private static String findFinishStepId(TaskLedger ledger) {
		if (ledger == null) {
			return null;
		}
		return ledger.steps().stream()
			.filter(step -> step.kind() == LedgerStepKind.FINISH)
			.map(LedgerStep::id)
			.findFirst()
			.orElse(null);
	}

	private static TaskLedger updateStepStatus(TaskLedger ledger, String stepId, LedgerStepStatus status) {
		if (ledger == null || stepId == null) {
			return ledger;
		}
		return new TaskLedger(
			ledger.missionId(),
			ledger.missionType(),
			ledger.goalText(),
			ledger.steps().stream()
				.map(step -> step.id().equals(stepId)
					? new LedgerStep(step.id(), step.kind(), step.args(), step.dependsOn(), status, step.expectedEvidence(), step.retryBudget(), step.notes())
					: step)
				.toList(),
			ledger.activeStepId(),
			ledger.completionCriteria(),
			ledger.replanReason(),
			ledger.plannerNotes()
		);
	}

	private static boolean completionSatisfied(List<EvidenceRequirement> completionCriteria, WorldEvidence evidence) {
		return completionCriteria != null
			&& !completionCriteria.isEmpty()
			&& completionCriteria.stream().allMatch(requirement -> evidenceSatisfied(requirement, evidence));
	}

	private static boolean evidenceSatisfied(EvidenceRequirement requirement, WorldEvidence evidence) {
		if (requirement == null) {
			return false;
		}
		return switch (requirement.type()) {
			case INVENTORY_DELTA_AT_LEAST -> requirement.resourceKind() != null
				&& requirement.quantity() != null
				&& evidence != null
				&& evidence.inventoryCounts().getOrDefault(requirement.resourceKind(), 0) >= requirement.quantity();
			case ITEM_COUNT_AT_LEAST -> requirement.itemId() != null
				&& requirement.quantity() != null
				&& evidence != null
				&& evidence.itemCounts().getOrDefault(requirement.itemId(), 0) >= requirement.quantity();
			case ITEM_DELTA_AT_LEAST -> false;
			case STEP_COMPLETED -> true;
			case POSITION_REACHED, RECIPE_CRAFTED, ITEMS_TRANSFERRED, BLOCK_PLACED, MISSION_FINISHED -> false;
		};
	}

	private static TaskSpec legacySpecFromLedger(TaskLedger ledger) {
		if (ledger == null) {
			return null;
		}
		return activeStep(ledger)
			.filter(step -> step.kind() == LedgerStepKind.COLLECT_RESOURCE && step.args().collectResource() != null)
			.map(step -> new TaskSpec(
				TaskType.COLLECT_RESOURCE,
				step.args().collectResource().resourceKind(),
				step.args().collectResource().quantity()
			))
			.orElse(null);
	}
}
