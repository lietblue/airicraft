package ai.moeru.airicraft.agent.actions;

import java.util.Objects;

public final class ActionFactPersistencePolicy {
	private ActionFactPersistencePolicy() {
	}

	public static ActionFactDurability durability(ActionFact fact) {
		Objects.requireNonNull(fact, "fact");
		if (fact.provenance() == ActionFactProvenance.STALE || fact.staleAfterTick() != ActionFact.NEVER_STALE) {
			return ActionFactDurability.VOLATILE;
		}
		return switch (fact.identity().type()) {
			case WORLD_BLOCK,
				WORLD_CROP,
				WORLD_CROP_GROUP,
				WORLD_SITE,
				WORLD_FARM_SITE,
				WORLD_FARM_PLOT,
				WORLD_SOIL_CANDIDATE,
				WORLD_HYDRATION_SOURCE,
				WORLD_LIGHT_LEVEL,
				WORLD_CROP_SEED_SOURCE,
				WORLD_ENTITY,
				WATCH_FULFILLED -> ActionFactDurability.PERSISTENT;
			case INVENTORY_ITEM, INVENTORY_RESOURCE, INVENTORY_TOOL, CRAFT_RECIPE, SMELT_RECIPE, WATCH_PENDING, ROUTE_FAILURE -> ActionFactDurability.VOLATILE;
		};
	}

	public static boolean persistedByDefault(ActionFact fact) {
		return durability(fact).persistedByDefault();
	}
}
