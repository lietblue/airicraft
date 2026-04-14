package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DialogueCore {
	static final int DEGRADED_FAILURE_THRESHOLD = 3;
	static final String RESET_COMMAND = "@agent reset";
	static final String DEGRADED_MESSAGE = "I'm having trouble understanding right now. Send '@agent reset' to recover my planner.";
	static final String RESET_MESSAGE = "Planner state reset.";
	static final String PARSE_ERROR_MESSAGE = "I got confused for a moment.";

	private DialogueCore() {
	}

	public static DialogueState initialState() {
		return DialogueState.initial();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		if (plainTextMessage == null) {
			return false;
		}
		return plainTextMessage.stripLeading().equalsIgnoreCase(RESET_COMMAND);
	}

	public static DialogueState markReplyObserved(DialogueState state) {
		return state.withPendingReply(false);
	}

	public static DialogueTransition onPlannerSuccess(DialogueState state, PlannerResponse plannerResponse, long tick) {
		DialogueIntentType mappedIntentType = DialogueIntentType.fromWire(plannerResponse.intent().type()).orElse(null);
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		if (mappedIntentType == null) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.unknown_intent", Map.of(
				"type", plannerResponse.intent().type()
			)));
			mappedIntentType = DialogueIntentType.NONE;
		}

		DialogueResponse response = new DialogueResponse(
			plannerResponse.replyText() == null ? "" : plannerResponse.replyText(),
			new DialogueIntent(
				mappedIntentType,
				plannerResponse.intent().goalType(),
				plannerResponse.intent().targetPlayer(),
				plannerResponse.intent().position(),
				plannerResponse.intent().mineSpec(),
				plannerResponse.intent().taskSpec(),
				plannerResponse.intent().taskLedger(),
				plannerResponse.intent().activeJob()
			),
			tick,
			plannerResponse.eventPolicyChanges()
		);
		DialogueState nextState = state
			.withConsecutiveFailureCount(0)
			.withLastResponse(response)
			.withPendingReply(hasVisibleText(response));
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	public static DialogueTransition onPlannerFailure(DialogueState state, LlmFailureType failureType, String failureMessage, long tick) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent(failureEventType(failureType), Map.of(
			"failureType", failureType.name(),
			"message", failureMessage == null ? "" : failureMessage
		)));

		ArrayList<DialogueResponse> visibleResponses = new ArrayList<>();
		if (failureType == LlmFailureType.PARSE_ERROR) {
			visibleResponses.add(new DialogueResponse(
				PARSE_ERROR_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		int consecutiveFailureCount = state.consecutiveFailureCount() + 1;
		boolean degraded = state.degraded();
		if (consecutiveFailureCount >= DEGRADED_FAILURE_THRESHOLD && !degraded) {
			degraded = true;
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_entered", Map.of(
				"failureType", failureType.name(),
				"consecutiveFailureCount", consecutiveFailureCount
			)));
			visibleResponses.add(new DialogueResponse(
				DEGRADED_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		DialogueResponse lastResponse = visibleResponses.isEmpty() ? state.lastResponse() : visibleResponses.get(visibleResponses.size() - 1);
		DialogueState nextState = state
			.withLastFailureType(failureType)
			.withLastFailureTick(tick)
			.withConsecutiveFailureCount(consecutiveFailureCount)
			.withDegraded(degraded)
			.withLastResponse(lastResponse)
			.withPendingReply(lastResponse != null && hasVisibleText(lastResponse));
		return new DialogueTransition(nextState, List.copyOf(visibleResponses), List.copyOf(effects));
	}

	public static DialogueTransition onReset(DialogueState state, String senderName, long tick) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent("planner.reset_requested", Map.of(
			"player", senderName
		)));
		if (state.degraded()) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", Map.of()));
		}

		DialogueResponse response = new DialogueResponse(
			RESET_MESSAGE,
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, senderName),
			tick
		);
		DialogueState nextState = DialogueState.initial()
			.withLastResponse(response)
			.withPendingReply(true);
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	private static boolean hasVisibleText(DialogueResponse response) {
		return response != null && response.text() != null && !response.text().isBlank();
	}

	private static String failureEventType(LlmFailureType failureType) {
		return switch (failureType) {
			case TIMEOUT -> "planner.timeout";
			case PARSE_ERROR -> "planner.parse_error";
			case PROVIDER_ERROR, PROVIDER_UNAVAILABLE -> "planner.provider_error";
		};
	}
}
