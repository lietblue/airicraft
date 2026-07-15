# Optimus-3 Modal Motor Pilot

## Purpose

Determine whether the released Optimus-3 action policy is a viable learned
motor beneath Airicraft's action graph before building a Java integration.

The pilot addresses the narrow hypothesis that a neural motor can execute local
movement, aiming, and attack more naturally than Baritone while Airicraft keeps
deterministic ownership of intent, completion, safety, and recovery.

## Architecture Boundary

```text
Airicraft planner
  -> action graph
    -> cached Optimus-3 task embedding
      -> Optimus-3 action policy
        -> Airicraft safety and actuator gate
          -> Minecraft
    <- deterministic completion and failure evidence
```

The planner remains responsible for high-level intent. The action graph remains
responsible for primitive selection and graph state. The learned motor proposes
low-level controls only. It cannot complete, fail, replan, or transfer a task.

## Pilot Stages

1. GPU artifact gate: load immutable upstream source and checkpoints on a Modal
   L40S, compute one task embedding, and run one synthetic-frame action.
2. Native reproduction: run ten locked MineStudio episodes and require at least
   eight successes plus a measured 20 Hz loop.
3. Airicraft shadow mode: send real 1.21.8 frames without applying actions and
   verify dimensions, recurrent resets, latency, and stale-result rejection.
4. Matched live A/B: intercept only one action-graph-owned visible-iron mining
   job and compare the neural backend against Baritone in a frozen world.
5. Cancellation and fallback: inject cancellation and inference timeouts, then
   require input release within one tick and deterministic Baritone recovery.

Only stage 1 is implemented by the initial Modal setup.

## Stage 1 Contract

Inputs are a fixed task string, deterministic seed, and synthetic neutral
`128x128x3` RGB frame. The full Optimus-3 model generates and caches a
`1x1x3584` task embedding. The released action head consumes that embedding and
one frame, producing camera, movement, jump, sprint, sneak, attack, inventory,
hotbar, use, drop, and escape controls.

The pilot records both raw and applied actions. The applied action masks escape,
inventory, hotbar, use, drop, pick-item, and swap-hands to zero. Masked attempts
remain durable evidence rather than disappearing behind the safety layer.

Stage 1 uses SDPA. It is a correctness and compatibility gate, not the final
latency configuration.

## Reproducibility and Resource Boundary

- Pin Optimus-3, LLaMA-Factory, the full model, action head, and auxiliary CLIP
  tokenizer by immutable commit.
- Cache public model assets in a dedicated persistent Modal Volume using a CPU
  function before starting the GPU model.
- Use one ephemeral L40S App with no deployment, detached execution, warm
  containers, or public endpoint.
- Bound startup and inference with separate timeouts, one container, one input,
  and zero configured retries.
- Write a local manifest and result bundle under ignored `eval-output/`.
- Never commit source clones, checkpoints, credentials, captured frames, or
  inference outputs.

## Stage 1 Acceptance

The gate passes only when:

- the runtime imports on an L40S with the pinned package versions;
- all expected checkpoint revisions are present in the Volume;
- the default task routes to `<iron>`;
- the embedding shape is exactly `1x1x3584` and all values are finite;
- the full action policy returns a complete, serializable action dictionary;
- every forbidden control is zero in the applied action; and
- the result records load time, embedding time, action time, GPU model, CUDA
  version, memory allocation, and all immutable revisions.

This pass does not authorize an Airicraft actuator integration. It authorizes
the native episode and performance stage.

## Later Live-Pilot Gate

The eventual visible-iron A/B proceeds only if native reproduction succeeds.
The neural arm must achieve at least 80% success and remain within 10 percentage
points of Baritone, have p95 closed-loop latency at or below 50 ms with fewer
than 5% missed deadlines, apply no forbidden action, release control within one
tick on cancellation, and receive at least 65% blinded naturalness preference.

Airicraft's inventory observation remains the source of truth for completion.
The production path retains Baritone fallback, although fallback is disabled
during scored neural trials to preserve attribution.

## Non-Goals

- Replacing the planner, action graph, verifier, or all Baritone execution.
- Deploying a persistent inference service.
- Fine-tuning Optimus-3 or pretraining from gameplay video.
- Claiming Minecraft 1.21.8 compatibility from a synthetic frame.
- Treating one inference latency as evidence of sustained 20 Hz control.
- Adding Fabric, bridge, evaluator, or wrapper integration in this setup.
