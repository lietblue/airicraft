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
			case "crafting.item_crafted" -> craftedActor(event) + " crafted " + craftedItemCount(event) + "x " + craftedItemId(event) + " " + relativeTime + ".";
			case "pickup.item_picked_up" -> craftedActor(event) + " picked up " + craftedItemCount(event) + "x " + craftedItemId(event) + " " + relativeTime + ".";
			case "follow.target_acquired" -> "Started following " + playerName(event) + " " + relativeTime + ".";
			case "follow.target_lost" -> "Lost the follow target " + playerName(event) + " " + relativeTime + ".";
			case "follow.stuck" -> "Movement got stuck while following " + playerName(event) + " " + relativeTime + ".";
			case "planner.goal_set" -> "The planner set goal " + goalName(event) + " " + relativeTime + ".";
			case "planner.goal_cleared" -> "The planner cleared goal " + goalName(event) + " " + relativeTime + ".";
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

	private static String craftedItemId(SemanticEvent event) {
		Object itemId = event.payload().get("itemId");
		if (itemId == null || String.valueOf(itemId).isBlank()) {
			return "an item";
		}
		return String.valueOf(itemId);
	}

	private static int craftedItemCount(SemanticEvent event) {
		Object count = event.payload().get("count");
		if (count instanceof Number number) {
			return Math.max(1, number.intValue());
		}
		if (count == null) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(String.valueOf(count)));
		} catch (NumberFormatException ignored) {
			return 1;
		}
	}

	private static String craftedActor(SemanticEvent event) {
		Object actor = event.payload().get("actor");
		if (actor == null || String.valueOf(actor).isBlank()) {
			return "Someone";
		}
		String actorValue = String.valueOf(actor);
		if ("self".equals(actorValue)) {
			return "You";
		}
		return actorValue;
	}
}
