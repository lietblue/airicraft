# Action Graph Foundation Smoke Checklist

Use this checklist after the architecture spec and foundation shell land, before starting the compatibility-wrapper migration.

## Scope

This is a foundation smoke, not a scenario acceptance run. It verifies that the graph package, actionset library, bridge inspection surface, and v1 inventory-item goal shell are present and internally consistent.

## Preconditions

- Branch is based on `dev`.
- `.envrc` is sourced before Gradle or wrapper commands.
- A running Minecraft client is required only for live bridge commands.
- The graph can dispatch only through existing primitive executors. Do not add a second execution path.

## Static Smoke

Run:

```bash
rg -n 'class PrimitiveActionRegistry|record ActionFact|class ActionFactStore|class ActionsetValidator|class ActionResolver|class ActionGraphDebugService' src/client/java/ai/moeru/airicraft/agent/actions
```

Expected:

- Primitive registry, facts, fact store, validator, resolver, and debug service are present.
- No planner, evaluator, compat launcher, or unrelated runtime files are required for this check.

Run:

```bash
rg -n 'action_graph.goal_started|startActionGoal|ActionGraphExecutionRuntime|/v1/agent/action-goals|agent actions goal' src/client/java wrapper/src/main/java
```

Expected:

- `EmbodiedAgentRuntime` owns the action goal shell.
- The bridge exposes `GET`, `POST`, and `DELETE /v1/agent/action-goals`.
- The wrapper exposes `agent actions goal inspect`, `agent actions goal start`, and `agent actions goal cancel`.

## Test Smoke

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests 'ai.moeru.airicraft.agent.actions.*' --tests ai.moeru.airicraft.agent.EmbodiedAgentRuntimeTest --tests ai.moeru.airicraft.ModBridgeServerTest
```

Expected:

- Actionset parsing, validation, resolution, graph execution runtime, runtime shell, and bridge compile/test coverage pass.

Run:

```bash
source .envrc && ./gradlew wrapper:test --tests ai.moeru.airicraft.wrapper.AiricraftCliMainTest
```

Expected:

- CLI rendering and transport payloads for graph inspection and action-goal shell commands pass.

## Live Bridge Smoke

After starting a dev or compat client and joining a world, run:

```bash
source .envrc && ./gradlew wrapper:installDist
wrapper/build/install/airicraft/bin/airicraft agent actions inspect
wrapper/build/install/airicraft/bin/airicraft agent actions goal inspect
wrapper/build/install/airicraft/bin/airicraft agent actions goal start --item-id minecraft:bread --quantity 1
wrapper/build/install/airicraft/bin/airicraft agent actions goal inspect --verbose
wrapper/build/install/airicraft/bin/airicraft agent actions goal cancel
```

Expected:

- `agent actions inspect` reports primitive, actionset, provider, and diagnostic counts.
- `agent actions goal start` returns an action graph `executionId` and a non-idle state.
- `agent actions goal inspect --verbose` includes the graph trace.
- `agent actions goal cancel` transitions the graph goal through one cancellation path and cancels any active foreground primitive.

## Draft Trial Smoke

Draft trial smoke remains local to the action graph package until operator authoring commands are wired.

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.actions.ActionsetDraftTrialRuntimeTest --tests ai.moeru.airicraft.agent.actions.ActionsetAuthoringServiceTest
```

Expected:

- Draft actionsets can be written, loaded, trialed, and reported without changing planner behavior.

## Stop Conditions

Stop and fix the foundation before moving to compatibility wrappers if any of these are true:

- The graph requires the planner to sequence low-level recipe, smelting, movement, or block steps.
- A graph-backed direct tool cannot return a graph execution or trace identity.
- A foreground primitive can be active outside `ActiveJobRuntime` and `WorldTaskExecutor`.
- Cancellation can leave the graph and active primitive in different terminal states.
