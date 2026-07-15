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
    -> cached Optimus-3 task embedding and deterministic projection
      -> per-frame stochastic prior and recurrent action policy
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
2. GPU latency screen: preserve the released stochastic prior and recurrent
   policy while caching deterministic task conditioning, then require a warmed
   20 Hz action loop.
3. Native reproduction: run ten locked MineStudio episodes and require at least
   eight successes.
4. Airicraft shadow mode: send real 1.21.8 frames without applying actions and
   verify dimensions, recurrent resets, latency, and stale-result rejection.
5. Matched live A/B: intercept only one action-graph-owned visible-iron mining
   job and compare the neural backend against Baritone in a frozen world.
6. Cancellation and fallback: inject cancellation and inference timeouts, then
   require input release within one tick and deterministic Baritone recovery.

Stages 1 and 2 are implemented by the Modal pilot. They remain synthetic GPU
gates and do not authorize a Minecraft actuator integration.

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
- Disable Modal's automatic source inclusion and explicitly upload only the
  pilot's `modal_app.py` and secret-free `contract.py`.
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
the Stage 2 latency screen only.

## Stage 2 Latency Contract

The default run uses eight exact released-path reference calls, then an
independent policy reset and seed for 32 warm-up plus 256 measured cached-path
steps. Recurrent state is continuous across warm-up and measurement. The fixed
synthetic frame is not paced or changed between steps.

Only the deterministic `1x1x3584 -> 1x512` projection is cached. The stochastic
prior is sampled each frame, followed by the released classifier-free-guidance
recurrent policy and stochastic action sampling. A pinned-source audit indicates
that removing the released dead MineCLIP lookup is distribution-equivalent, but
the pilot does not statistically test that assumption. It also changes RNG
consumption, so the paths are not same-seed trace-equivalent.

Each timed native step includes frame preprocessing and transfer, stochastic
prior, recurrent inference, action sampling and mapping, fail-closed action
schema validation, normalization, and the safety mask. It excludes model load,
one-time MLLM embedding and projection, Minecraft capture, transport, tick
scheduling, and actuator application. CUDA is synchronized around every timed
step, and nearest-rank percentiles are used.

Stage 2 passes only when the full run completes with at least 200 continuous
measured steps, p95 native-step latency is at most 50 ms, strictly fewer than 5%
of samples exceed 50 ms, every action schema is complete, and no safety-mask
violation occurs. Passing this screen authorizes ten locked native MineStudio
episodes; it is not evidence of task competence or live closed-loop latency.

## Verified Stage 1 Result

The 2026-07-15 Modal run passed the Stage 1 correctness gate:

- the pinned runtime imported both released model classes on an NVIDIA L40S
  with 47,665,709,056 bytes of device memory;
- the Volume cached 22,160,412,506 bytes across the three pinned snapshots;
- `collect one iron ore` routed to `<iron>` and produced a finite
  `1x1x3584` embedding with SHA-256
  `19df8b793320e5b48aa835f09e5faa10e82283986c84f805a691e4f86d34949b`;
- the normalized raw gray-frame action was `forward + left + use`; the safety mask
  recorded the forbidden `use` attempt and zeroed it in the applied action;
- model load took 68.347 seconds, the one-time task embedding took 0.582
  seconds, and the first recurrent action took 0.434 seconds; and
- peak allocated CUDA memory was 21,345,110,016 bytes.

The local evidence is retained under `eval-output/optimus3-modal/` and remains
ignored by Git. The 434 ms first action is not evidence of 20 Hz operation; the
warmed native multi-step latency measurement in Stage 2 is the next gate,
before native episodes or any Minecraft actuator work.

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
