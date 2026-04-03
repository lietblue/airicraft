package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticEventNoticeFormatterTest {
	@Test
	void pickupEventUsesYouActorAndItemDetails() {
		SemanticEvent event = new SemanticEvent(
			1L,
			10L,
			1_000L,
			"pickup.item_picked_up",
			Map.of(
				"actor", "self",
				"itemId", "minecraft:oak_log",
				"count", 2
			)
		);

		assertEquals("You picked up 2x minecraft:oak_log just now.", SemanticEventNoticeFormatter.format(event, 1_000L));
	}

	@Test
	void pickupEventFallsBackForMissingFields() {
		SemanticEvent event = new SemanticEvent(
			2L,
			10L,
			1_000L,
			"pickup.item_picked_up",
			Map.of()
		);

		assertEquals("Someone picked up 1x an item just now.", SemanticEventNoticeFormatter.format(event, 1_000L));
	}
}
