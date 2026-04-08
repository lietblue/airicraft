# Resource Task Runtime Design

## Summary

Airicraft already has two separate paths:

- A planner path that turns chat into `DialogueIntent` and optional `GoalSnapshot`.
- A world-execution path that turns `GoalSnapshot` into primitive Baritone actions through `WorldTaskExecutor`.

The next feature should not overload the existing one-shot vision tool loop with long-running world work. Instead, Airicraft should gain a first-class task layer that sits between planner semantics and primitive Baritone goals.

The initial scope is intentionally narrow:

- Let the planner request high-level resource collection.
- Let the runtime decompose that request into primitive goals.
- Keep LLM responsibility at the semantic level.
- Expose the same task surface through the wrapper for live debugging.
- Add instrumentation so task state, substeps, and failures are visible end to end.

## Goals

- Support end-to-end planner-driven collection of raw wood logs.
- Keep world-execution details out of the LLM prompt contract.
- Preserve the existing primitive goal path for follow, navigate, and direct block mining.
- Provide a debug surface that can submit, inspect, and cancel tasks without waiting for planner output.
- Make live failures diagnosable through bridge and wrapper observability.

## Non-Goals

- No crafting, smelting, chest interaction, or general inventory automation.
- No combat, hazard recovery, or fully autonomous survival behavior.
- No generalized task planner that chains arbitrary subgoals.
- No replacement of the existing `take_a_look` tool flow.
- No attempt to solve every resource type in v1.

## Recommended Approach

Introduce a first-class task runtime and keep goals as primitive execution units.

Planner output should gain task-level intents such as `submit_task` and `cancel_task`. Those intents should not directly describe how to move or mine. Instead they should carry a structured `TaskSpec`. A new `TaskRuntime` should own task progress, decompose tasks into primitive goals, and feed those goals into the existing goal and Baritone execution path.

This is preferable to either:

- Stuffing high-level resource collection into `GoalType`, which blurs semantic and execution boundaries.
- Reusing `toolRequest`, which is currently a one-shot, single-tool loop designed around vision follow-up and not around long-running world tasks.

## Task Model

### Task Types

V1 supports a single task family:

- `COLLECT_RESOURCE`

The payload is:

```json
{
  "type": "COLLECT_RESOURCE",
  "resourceKind": "WOOD_LOGS",
  "quantity": 16
}
```

`WOOD_LOGS` is a semantic resource family, not a specific block id. The runtime resolves it to allowed block ids and accepted inventory items. This lets the planner say "collect wood" without micro-managing species selection.

### Why `resourceKind` Instead Of Raw Item IDs

The user intent is semantic, and the runtime should absorb the operational mapping. In v1, `WOOD_LOGS` resolves to both:

- target blocks: overworld log blocks that can be directly chopped
- accepted inventory items: matching log items

This keeps the prompt simpler and gives the runtime room to choose nearby candidates.

## Planner Contract

### Output Shape

The planner response should stay JSON-first, but the semantic action space expands:

```json
{
  "replyText": "On it.",
  "intent": {
    "type": "submit_task" | "cancel_task" | "set_goal" | "clear_goal" | "reply_only" | "ask_clarification" | "acknowledge_failure" | "none",
    "goalType": "FOLLOW_PLAYER" | "NAVIGATE_TO" | "MINE_BLOCKS" | null,
    "targetPlayer": "string | null",
    "position": { "x": 0, "y": 64, "z": 0, "exactY": true } | null,
    "mineSpec": { "blockIds": ["minecraft:oak_log"], "quantity": 4 } | null,
    "taskSpec": {
      "type": "COLLECT_RESOURCE",
      "resourceKind": "WOOD_LOGS",
      "quantity": 16
    } | null
  },
  "toolRequest": {
    "type": "take_a_look",
    "prompt": "string | null"
  } | null
}
```

### Planner Rules

- The planner may use `submit_task` for high-level collection asks such as "get me wood" or "collect 16 logs".
- The planner should continue to use direct `set_goal` for explicit primitive requests such as "go to x y z" or "mine this exact block type".
- The planner must not emit both a task submission and a goal-setting action in the same response.
- The planner may still use `take_a_look` before submitting a task if visual clarification is needed, but the actual long-running world work must start through `submit_task`, not `toolRequest`.
- If the requested resource is outside v1 scope, the planner should ask for clarification or state that the task is unsupported.

## Runtime Architecture

### New Components

- `TaskSpec`
  - immutable high-level task payload
- `TaskType`
  - enum for supported task families
- `TaskSnapshot`
  - externally visible task state
- `TaskState`
  - `IDLE`, `QUEUED`, `RUNNING`, `WAITING_FOR_PICKUP`, `COMPLETED`, `FAILED`, `CANCELLED`, `PAUSED_BY_SESSION_GATE`
- `TaskRuntime`
  - owns the active high-level task and current substep
- `TaskPlanner` or `TaskHandler`
  - strategy interface for decomposing a task type into primitive steps
- `CollectResourceTaskHandler`
  - v1 implementation for `WOOD_LOGS`

### Existing Components Reused

- `GoalDirector`
  - continues to own the active primitive goal
- `WorldTaskExecutor`
  - continues to execute primitive goals
- `BaritoneTaskExecutor`
  - continues to translate primitive goals into Baritone API calls
- `DialogueRuntime`
  - continues to own planner interactions, but now also accepts task intents
- `EmbodiedAgentRuntime`
  - becomes the coordinator between task runtime, goal runtime, session state, and semantic events

### Boundary

- `TaskRuntime` decides what primitive goal should be active now.
- `GoalDirector` and `WorldTaskExecutor` do not know why that goal exists.
- `BaritoneTaskExecutor` never sees a semantic resource task.

## `COLLECT_RESOURCE(WOOD_LOGS)` Decomposition

### High-Level Loop

For the active task, the runtime repeatedly performs:

1. Capture a baseline count of accepted inventory items.
2. Find a nearby viable tree target.
3. Emit a primitive `NAVIGATE_TO` goal to approach the target.
4. Emit a primitive `MINE_BLOCKS` goal for the resolved log block ids.
5. Wait for mined items to be picked up.
6. Recount inventory.
7. If collected quantity is still below target, repeat with a new target.

### Candidate Resolution

V1 should stay simple and local:

- search in a bounded radius around the player
- only consider directly visible or straightforward nearby log candidates
- prefer closest reachable candidate
- skip candidates already attempted recently
- fail after a bounded number of unsuccessful attempts

The runtime can use direct world inspection rather than vision for this. It already runs inside the client and has access to the loaded world.

### Completion Rule

Task completion is based on inventory delta, not only Baritone terminal events.

This matters because:

- a mine step can finish while the dropped item is still on the ground
- a tree can be partially chopped with no useful pickup
- Baritone reaching a mine terminal state does not guarantee semantic success

## State Machine

### Task State

- `IDLE`
  - no active task
- `QUEUED`
  - task accepted, first world probe not yet performed
- `RUNNING`
  - currently executing a primitive step
- `WAITING_FOR_PICKUP`
  - primitive mining step ended, waiting briefly for inventory delta to settle
- `PAUSED_BY_SESSION_GATE`
  - task preserved but actuation blocked
- `COMPLETED`
  - semantic quantity reached
- `FAILED`
  - no viable target, repeated execution failure, or unrecoverable timeout
- `CANCELLED`
  - user, planner, or world/session reset cancelled the task

### Primitive Goal Ownership

Only the task runtime should author the primitive goals for a task-owned execution. When the active intent is a direct `set_goal`, that goal continues to bypass the task layer.

This means Airicraft needs explicit ownership rules:

- direct planner `set_goal` owns the goal directly
- planner `submit_task` owns the goal indirectly through `TaskRuntime`
- cancelling a task must clear any task-owned primitive goal
- setting a direct goal should cancel the active task first

## Failure Handling

### Recoverable Failures

The task runtime may retry on:

- `NAVIGATE_TO` cancelled by a stale target
- `MINE_BLOCKS` path calculation failure for one candidate
- a candidate that produced no inventory gain

Retries should be bounded and visible in instrumentation.

### Terminal Failures

The task fails when:

- no viable target is found within the configured search radius
- repeated candidates make no semantic progress
- session/world transitions invalidate the task and it cannot be resumed safely
- the runtime hits a step timeout budget

### Planner Feedback

Task terminal events should generate the same kind of internal planner callback already used for primitive goal terminal updates, but with task-level context:

- task submitted
- task progress updated
- task completed
- task failed
- task cancelled

The planner can then send a visible chat reply, remain silent, or propose the next step.

## Instrumentation

### Snapshot Surface

`AgentRuntimeSnapshot` should expose a new `task` block distinct from `taskExecution`:

```json
{
  "task": {
    "state": "RUNNING",
    "spec": {
      "type": "COLLECT_RESOURCE",
      "resourceKind": "WOOD_LOGS",
      "quantity": 16
    },
    "progress": {
      "collected": 7,
      "remaining": 9
    },
    "currentStep": "MINE_TARGET",
    "attemptCount": 2,
    "candidateTarget": { "x": 12, "y": 64, "z": -4 },
    "lastFailure": null
  }
}
```

The existing `taskExecution` block should remain focused on the primitive executor:

- executor state
- active primitive goal
- active Baritone process name
- last path event
- estimated ticks to goal

### Semantic Events

Add stable task events:

- `task.submitted`
- `task.progress`
- `task.step_started`
- `task.step_completed`
- `task.step_failed`
- `task.completed`
- `task.failed`
- `task.cancelled`
- `task.paused_by_session_gate`

Each should include enough payload to explain:

- which task
- which step
- what resource target
- what progress changed

## Bridge And Wrapper

### Bridge Endpoints

Add:

- `GET /v1/agent/tasks`
  - current task snapshot
- `POST /v1/agent/tasks`
  - submit a task directly for debugging
- `DELETE /v1/agent/tasks`
  - cancel the active task

These are debug and observability surfaces. They intentionally mirror the runtime contract instead of exposing Baritone internals.

### Wrapper Commands

Add:

- `airicraft agent tasks`
- `airicraft agent tasks submit --type collect-resource --resource wood-logs --quantity 16`
- `airicraft agent tasks cancel`

These commands should coexist with existing observability commands and print deterministic structured output.

## Testing Strategy

### Unit Tests

- planner parsing for `submit_task` and `cancel_task`
- prompt-policy tests for the expanded planner contract
- task runtime state machine tests
- collect-resource decomposition tests
- ownership tests between task-owned goals and direct goals
- bridge and wrapper request/response tests for task endpoints

### Integration Tests

- `DialogueRuntime` accepts `submit_task` and starts task runtime
- task runtime emits primitive goals in the expected order
- inventory delta completes the task
- cancelling a task clears the task-owned primitive goal
- a direct `set_goal` preempts an active task cleanly

### Live Verification

The live path should be split into deterministic checkpoints instead of a single opaque scenario:

1. wrapper task submission reaches the mod runtime
2. task snapshot changes to `RUNNING`
3. primitive executor shows a real Baritone process
4. progress increases after mining and pickup
5. task completes once target quantity is reached
6. planner receives the internal terminal update and remains coherent afterward

For the first end-to-end run, use a controlled singleplayer world with a nearby tree and minimal hostile interference. The current survival-only world is too noisy for reliable completion.

## Migration Notes

- Existing primitive goal support stays intact.
- Existing Baritone executor code is not thrown away; it becomes the primitive engine under the new task layer.
- Existing plan and verification work for `NAVIGATE_TO` and `MINE_BLOCKS` remains useful because resource collection is built on top of those primitives.

## Open Design Decisions Closed By This Spec

- High-level world work uses a dedicated task layer, not `toolRequest`.
- The first semantic resource family is `WOOD_LOGS`.
- Debugging uses both wrapper task submission and snapshot instrumentation.
- Completion is semantic and inventory-based, not merely Baritone-terminal-state-based.
- Primitive goals remain the only thing the Baritone executor runs.
