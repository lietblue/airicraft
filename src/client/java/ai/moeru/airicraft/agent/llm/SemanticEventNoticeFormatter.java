package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;

public final class SemanticEventNoticeFormatter {
	private SemanticEventNoticeFormatter() {
	}

	public static String format(SemanticEvent event, long anchorTimeMs) {
		String relativeTime = RelativeTimeFormatter.format(event.timestampMs(), anchorTimeMs);
		return switch (event.type()) {
			case "session.world_loaded" -> {
				Object dimension = event.payload().get("dimensionId");
				yield dimension == null
					? "A world loaded " + relativeTime + "."
					: "A world loaded " + relativeTime + " in " + dimension + ".";
			}
			case "session.world_unloaded" -> "The world unloaded " + relativeTime + ".";
			case "session.connection_lost" -> "The world connection was lost " + relativeTime + ".";
			case "session.lan_opened" -> {
				Object port = event.payload().get("port");
				yield port == null
					? "LAN sharing opened " + relativeTime + "."
					: "LAN sharing opened " + relativeTime + " on port " + port + ".";
			}
			case "follow.target_acquired" -> "Started following " + playerName(event) + " " + relativeTime + ".";
			case "follow.target_lost" -> "Lost the follow target " + playerName(event) + " " + relativeTime + ".";
			case "follow.stuck" -> "Movement got stuck while following " + playerName(event) + " " + relativeTime + ".";
			case "planner.goal_set" -> "The planner set goal " + goalName(event) + " " + relativeTime + ".";
			case "planner.goal_cleared" -> "The planner cleared goal " + goalName(event) + " " + relativeTime + ".";
			case "mission.ledger_updated" -> {
				Object missionId = event.payload().get("missionId");
				Object activeStepId = event.payload().get("activeStepId");
				Object previousActiveStepId = event.payload().get("previousActiveStepId");
				yield "The mission ledger for "
					+ (missionId == null ? "the active mission" : missionId)
					+ " changed "
					+ relativeTime
					+ ", active step moved from "
					+ (previousActiveStepId == null || String.valueOf(previousActiveStepId).isBlank() ? "none" : previousActiveStepId)
					+ " to "
					+ (activeStepId == null || String.valueOf(activeStepId).isBlank() ? "none" : activeStepId)
					+ ".";
			}
			case "planner.degraded_entered" -> "The planner entered degraded mode " + relativeTime + ".";
			case "planner.degraded_cleared" -> "The planner recovered from degraded mode " + relativeTime + ".";
			case "planner.reset_requested" -> "A planner reset was requested " + relativeTime + ".";
			default -> null;
		};
	}

	private static String playerName(SemanticEvent event) {
		Object player = event.payload().get("player");
		return player == null ? "the active player" : String.valueOf(player);
	}

	private static String goalName(SemanticEvent event) {
		Object goalType = event.payload().get("goalType");
		Object targetPlayer = event.payload().get("targetPlayer");
		if (goalType == null) {
			return "the current goal";
		}
		if (targetPlayer == null || String.valueOf(targetPlayer).isBlank()) {
			return String.valueOf(goalType);
		}
		return goalType + " for " + targetPlayer;
	}
}
