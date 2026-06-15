# Persistent Action Facts

## Goal

Persist only durable world knowledge for the action graph. Do not save transient executor observations as if they were stable world facts.

## World Scope

Persistent facts are stored under a world/server scope string. Bridge and wrapper commands accept `world-id`; when omitted, the runtime derives a scope from the current session mode and dimension.

The first disk store writes JSON files under:

```text
~/.airicraft/action-facts/
```

Each file is keyed by a sanitized world scope. Fact identity keys still carry their own `worldId`, dimension, actor, and object keys.

## Default Durability

Persist by default:

- `world.block`
- `world.crop`
- `world.crop_group`
- `world.site`
- `world.entity`
- `watch.fulfilled`

Keep volatile by default:

- `inventory.item`
- `inventory.tool`
- `craft.recipe`
- `watch.pending`
- `route.failure`

## Freshness Rules

- Facts with `provenance=STALE` are never persisted by default.
- Facts whose stale window is already expired at their observed tick are never persisted by default.
- Persisted facts retain `observedTick`, `staleAfterTick`, `provenance`, identity keys, and payload.
- Runtime users must still check freshness before using a fact for guards or route selection.

## CLI Contract

Persistent facts are inspectable through:

```bash
airicraft agent actions facts list [--world-id <scope>] [--type <fact.type>]
airicraft agent actions facts clear [--world-id <scope>]
```

Output is deterministic and includes fact type, identity keys, provenance, observed tick, stale tick, and payload in verbose mode.

## Non-Goals

- Do not persist current executable craftability.
- Do not persist inventory counts by default.
- Do not make the planner responsible for fact freshness decisions.
- Do not use persistent facts to bypass foreground primitive guards.
