# Action Graph Watches

## Goal

Watches let an action graph route wait for an async world condition without keeping a foreground primitive active.

## Runtime Contract

When execution reaches a watch step:

- Register a stable watch id scoped to the graph execution and step id.
- Transition the graph to `WATCHING`.
- Keep `activeTaskId` empty unless a foreground primitive is actually running.
- Emit trace events for registration, fulfillment, timeout, and cancellation.
- Resume route advancement only after the watched fact or terminal goal fact is observed.

Cancellation must use the same graph cancellation path as foreground primitive cancellation. If no primitive is active, cancelling a watch only transitions the graph goal to `CANCELLED`.

## Inspection Contract

The active goal payload exposes:

- `executionId`
- `state`
- `watchCount`
- `pendingWatch`
- `traceEventCount`
- verbose `trace`

Wrapper watch inspection is a view over the active graph goal:

```bash
airicraft agent actions watches list [--verbose]
```

## Persistence Boundary

Pending watches are volatile by default. Fulfilled watches may be persisted as durable facts if they represent reusable world knowledge.

The first persistent fact policy reflects this:

- `watch.pending` is volatile.
- `watch.fulfilled` is persistent by default.

## Non-Goals

- Do not block the planner prompt while a watch is pending.
- Do not keep Baritone or another primitive executor active for a passive condition wait.
- Do not make watch polling a planner responsibility.
