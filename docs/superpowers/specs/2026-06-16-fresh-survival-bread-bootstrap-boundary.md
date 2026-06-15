# Fresh Survival Bread Bootstrap Boundary

## Summary

The action graph bread vertical slice covers bread from controlled facts,
existing wheat, mature wheat, and growing wheat watches. It does not make a
fresh survival world self-bootstrap farming system part of the core resolver
scope.

Fresh survival bread bootstrap should be a later farming/building mode or
skill invoked by the planner as a high-level intent. The planner should still
not micromanage low-level steps; the later mode should author facts and graph
goals for the resolver to execute.

## Current Boundary

Core graph resolver scope:

- Resolve `inventory.item minecraft:bread` from existing bread facts.
- Resolve bread from executable or provider-backed `craft.recipe` facts.
- Resolve wheat from existing inventory facts.
- Resolve wheat from known mature `world.crop_group` facts.
- Suspend on known growing `world.crop_group` facts until maturity.
- Dispatch existing primitive bindings such as `mine_block` and `craft_item`.

Fresh bootstrap scope is explicitly deferred:

- Finding seeds from grass, villages, chests, or traded sources.
- Selecting or building a farm site from terrain.
- Checking water, light, soil, crop spacing, and access constraints.
- Tilling soil, planting crops, protecting the farm, and waiting across long
  horizons.
- Constructing missing infrastructure such as buckets, hoes, water channels,
  fences, paths, or lighting.

## Future Mode Shape

The later mode should be a graph-backed macro mode, not planner prompt
micromanagement.

Expected responsibility split:

- Planner: request a high-level outcome such as "bootstrap bread production" or
  "start a wheat farm".
- Farming/building mode: collect world observations, select candidate sites,
  author durable facts, and request graph goals.
- Action graph: own route selection, primitive dispatch, cancellation, watches,
  trace emission, and terminal success.
- Existing executors: remain primitive bindings for movement, block
  modification, item use, crafting, smelting, and collection.

## Missing Primitives

Likely primitive bindings or wrappers:

- `till_soil`
- `plant_crop`
- `hydrate_farmland` or water placement with farm-site guards
- `clear_farm_site`
- `prepare_farm_plot`
- `wait_world_ticks` or long-horizon watch scheduling
- `collect_seeds`
- `craft_or_select_tool`

## Missing Facts

Likely fact/provider surfaces:

- `world.farm_site`
- `world.farm_plot`
- `world.soil_candidate`
- `world.hydration_source`
- `world.light_level`
- `world.crop_seed_source`
- `inventory.tool` with tilling capability
- `watch.crop_maturity`
- `watch.farm_hydration`

## Acceptance For Current Merge

The current action graph foundation can merge when:

- Bread routes pass from controlled facts, mature wheat, and growing wheat
  watch facts.
- Fresh survival bootstrap remains documented as a separate future branch or
  milestone.
- Missing primitives and facts are explicit follow-up scope.

The current resolver is not required to solve full farm bootstrap from an empty
survival world.
