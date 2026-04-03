package ai.moeru.airicraft.agent.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticContextUpdateFormatterTest {
	@Test
	void pickupEventUsesYouActorAndAggregatedItemCount() {
		AggregatedSemanticEvent event = new AggregatedSemanticEvent(
			"pickup.item_picked_up",
			Map.of(
				"actor", "self",
				"itemId", "minecraft:oak_log",
				"count", 3
			),
			10L,
			1_000L,
			3,
			1L,
			3L
		);

		assertEquals("You picked up 3x minecraft:oak_log just now.", SemanticContextUpdateFormatter.format(event, 1_000L));
	}

	@Test
	void pickupEventFallsBackForMissingFields() {
		AggregatedSemanticEvent event = new AggregatedSemanticEvent(
			"pickup.item_picked_up",
			Map.of(),
			10L,
			1_000L,
			1,
			2L,
			2L
		);

		assertEquals("Someone picked up 1x an item just now.", SemanticContextUpdateFormatter.format(event, 1_000L));
	}
}
