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

	@Test
	void damageEventUsesAttackerWhenPresent() {
		AggregatedSemanticEvent event = new AggregatedSemanticEvent(
			"combat.damage_taken",
			Map.of(
				"actor", "self",
				"amount", 3.5F,
				"healthAfter", 16.5F,
				"attackerName", "Zombie"
			),
			12L,
			1_000L,
			1,
			4L,
			4L
		);

		assertEquals("You took 3.5 damage from Zombie and dropped to 16.5 health just now.", SemanticContextUpdateFormatter.format(event, 1_000L));
	}

	@Test
	void damageEventFallsBackToDamageTypeWhenAttackerIsUnknown() {
		AggregatedSemanticEvent event = new AggregatedSemanticEvent(
			"combat.damage_taken",
			Map.of(
				"actor", "self",
				"amount", 4.0F,
				"healthAfter", 12.0F,
				"damageTypeId", "minecraft:fall"
			),
			14L,
			1_000L,
			1,
			5L,
			5L
		);

		assertEquals("You took 4 damage from minecraft:fall and dropped to 12 health just now.", SemanticContextUpdateFormatter.format(event, 1_000L));
	}

	@Test
	void damageEventFallsBackWhenSourceDetailsAreMissing() {
		AggregatedSemanticEvent event = new AggregatedSemanticEvent(
			"combat.damage_taken",
			Map.of(
				"actor", "self",
				"amount", 2.0F,
				"healthAfter", 18.0F
			),
			15L,
			1_000L,
			1,
			6L,
			6L
		);

		assertEquals("You took 2 damage and dropped to 18 health just now.", SemanticContextUpdateFormatter.format(event, 1_000L));
	}
}
