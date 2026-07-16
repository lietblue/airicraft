package ai.moeru.airicraft.agent.motor;

import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionState;
import ai.moeru.airicraft.agent.actions.ActionPlanStep;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exact, deliberately narrow eligibility gate for the first live shadow slice. */
public final class Optimus3MotorShadowEligibility {
	private static final String RAW_IRON_ITEM = "minecraft:raw_iron";
	private static final List<String> IRON_BLOCKS = List.of(
		"minecraft:iron_ore",
		"minecraft:deepslate_iron_ore"
	);

	private Optimus3MotorShadowEligibility() {
	}

	public static MotorShadowEligibilityDecision evaluate(
		SessionSnapshot session,
		TaskSnapshot task,
		ActionGraphExecutionSnapshot graph,
		Optional<WorldTaskRequest> activeTask
	) {
		return evaluate(session, task, graph, activeTask, true);
	}

	public static MotorShadowEligibilityDecision evaluate(
		SessionSnapshot session,
		TaskSnapshot task,
		ActionGraphExecutionSnapshot graph,
		Optional<WorldTaskRequest> activeTask,
		boolean firstPersonPerspective
	) {
		if (session == null || !session.worldLoaded() || !session.companionActuationAllowed()) {
			return MotorShadowEligibilityDecision.ineligible("session_not_eligible");
		}
		if (!firstPersonPerspective) {
			return MotorShadowEligibilityDecision.ineligible("perspective_not_first_person");
		}
		if (task == null || !"action_graph".equals(task.source())) {
			return MotorShadowEligibilityDecision.ineligible("not_action_graph_owned");
		}
		if (graph == null || graph.state() != ActionGraphExecutionState.WAITING_PRIMITIVE) {
			return MotorShadowEligibilityDecision.ineligible("graph_not_waiting_primitive");
		}
		ActionPlanStep step = graph.currentStep();
		if (step == null || !"mine_block".equals(step.targetId()) || !isRawIronMine(step.args())) {
			return MotorShadowEligibilityDecision.ineligible("not_raw_iron_mine_block");
		}
		WorldTaskRequest request = activeTask == null ? null : activeTask.orElse(null);
		if (request == null || request.type() != WorldTaskType.MINE) {
			return MotorShadowEligibilityDecision.ineligible("world_task_not_mine");
		}
		if (graph.activeTaskId() == null || !graph.activeTaskId().equals(request.taskId())) {
			return MotorShadowEligibilityDecision.ineligible("graph_task_mismatch");
		}
		if (blank(graph.executionId()) || blank(step.actionId()) || blank(step.stepId())) {
			return MotorShadowEligibilityDecision.ineligible("graph_provenance_incomplete");
		}
		return MotorShadowEligibilityDecision.eligible(new MotorGraphIdentity(
			graph.executionId(),
			step.actionId(),
			step.stepId(),
			step.targetId(),
			graph.stepAttempt(),
			request.taskId(),
			request.type().name()
		));
	}

	private static boolean isRawIronMine(Map<String, Object> args) {
		if (args == null || !RAW_IRON_ITEM.equals(args.get("itemId"))) {
			return false;
		}
		Object rawBlockIds = args.get("blockIds");
		if (!(rawBlockIds instanceof List<?> blockIds)) {
			return false;
		}
		return blockIds.stream()
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.anyMatch(IRON_BLOCKS::contains);
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
