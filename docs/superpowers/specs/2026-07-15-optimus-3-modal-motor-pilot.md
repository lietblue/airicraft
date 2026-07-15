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
3. Semantic-equivalent native fixture gate: run the exact locked ten-episode
   MineStudio schedule, require all ten episodes and infrastructure/schema/safety
   checks to be valid, then require at least eight successes.
4. Airicraft shadow mode: send real 1.21.8 frames without applying actions and
   verify dimensions, recurrent resets, latency, and stale-result rejection.
5. Matched live A/B: intercept only one action-graph-owned visible-iron mining
   job and compare the neural backend against Baritone in a frozen world.
6. Cancellation and fallback: inject cancellation and inference timeouts, then
   require input release within one tick and deterministic Baritone recovery.

Stages 1 through 3 are implemented by the Modal pilot. Stages 1 and 2 are
synthetic GPU gates; Stage 3 is a native MineStudio gate. The evaluated Stage 3
result failed competence and does not authorize a Minecraft actuator
integration.

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

The fail-closed policy schema contains the 22 controls actually emitted by the
pinned VPT action transformer. `pickItem` and `swapHands` exist only in the
Airicraft actuator schema; normalization adds them as zero before producing the
24-key safety-masked action.

Stage 2 passes only when the full run completes with at least 200 continuous
measured steps, p95 native-step latency is at most 50 ms, strictly fewer than 5%
of samples exceed 50 ms, every action schema is complete, and no safety-mask
violation occurs. Passing this screen authorizes ten locked native MineStudio
episodes; it is not evidence of task competence or live closed-loop latency.

## Stage 3 Native Contract

Stage 3 uses the pinned MineStudio simple iron task source and released
simulator engine. The released engine drops the task's selector-relative `fill`
command, so the harness creates the same relative 2x2x2 iron state with
absolute `setblock` actions. It settles two no-op ticks, then queries half-open
voxel bounds `(2, 4, 0, 2, 2, 4)` and requires all eight cells to contain iron
before policy inference. Only missing cells are retried, for at most three
passes. The stone pickaxe is supplied deterministically by the mission's
starting inventory. This is semantic-equivalent fixture reproduction, not
upstream-command parity.

The locked schedule contains ten immutable world/policy seed pairs. Every
episode starts a fresh Minecraft process, performs hard reset, resets recurrent
policy state once, and allows at most 200 scored policy steps. There is no
warm-up, fallback, video recording, or policy access to the fixture voxel
oracle. The policy prompt is `collect one iron ore`, retained from Stages 1 and
2 for conditioning continuity; the upstream task text is recorded but is not
the model input. Initial line of sight and camera orientation are not fixed, so
this is not a visible-iron test.

Success requires both a positive `mine_block.iron_ore` delta and a positive
inventory `iron_ore` delta. The gate is evaluated only when the exact ten
records are present and valid and all pin, GPU, fixture, schema, and safety
checks pass. `timeout_200_steps` is a valid competence failure. Setup, reset,
schema, or close failures make the gate unevaluable. An evaluated pass requires
at least eight successes.

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
ignored by Git. The 434 ms first action was not evidence of 20 Hz operation;
the subsequent warmed native multi-step latency measurement was evaluated
separately by Stage 2.

## Verified Stage 2 Result

The corrected 2026-07-15 L40S run under `20260715T102957Z-bench` passed the
synthetic GPU latency screen:

- all eight released-path diagnostics, 32 warm-up steps, and 256 continuous
  measured steps completed with the required reset sequence;
- measured native-step latency was 6.737 ms p50, 6.780 ms p95, 6.812 ms p99,
  and 7.294 ms maximum;
- mean latency was 6.740 ms, or 148.36 sequential steps per second, with zero
  samples above the 50 ms deadline;
- all 296 policy outputs matched the exact 22-key schema, every applied action
  matched the 24-key actuator schema, and no safety-mask violation occurred;
- the measured policy attempted `use` on 145 steps and `hotbar.4` on one step;
  all 146 forbidden attempts were retained as evidence and masked to zero; and
- the pinned runtime, L40S device, checkpoints, task label, embedding and
  projection shapes, recurrent stream, and uploaded-source hashes all matched.

The eight released-path calls were not warm-state matched. Their 7.92x
reference-to-cached mean-latency ratio is diagnostic only and is not used by
the acceptance gate. The result establishes substantial compute-loop headroom
under the 50 ms budget, but excludes frame capture, transport, tick scheduling,
and Minecraft action application. It authorized the Stage 3 run described
below; it did not establish mining competence, naturalness, or live 1.21.8
performance.

An earlier attempt under `20260715T102647Z-bench` stopped fail-closed at its
first reference action because the harness incorrectly required the
actuator-only `pickItem` and `swapHands` controls from Optimus. No warm-up or
measured samples ran in that attempt. Commit `05304ae` separated the pinned
22-key policy schema from the 24-key actuator schema before the successful run.

## Verified Stage 3 Result

The native fixture was first proven on CPU under
`20260715T131453Z-sim-preflight`: the released engine accepted absolute
`setblock` actions, the half-open voxel oracle found all eight cells, the
pickaxe and initial inventory were correct, and the action adapter passed. A
single L40S episode under `20260715T131739Z-episode` then completed at step 114,
proving that the released policy can solve at least one native instance.

The first ten-episode attempt under `20260715T132001Z-episodes` reused one
Minecraft JVM. It produced six valid records and two successes before the
seventh reset timed out. That run is invalid and unevaluated; its partial `2/6`
score is not a competence result. The harness was changed to create and close a
fresh simulator process for each episode. The exact lifecycle seam then passed
19 CPU checks across two processes under
`20260715T133227Z-sim-preflight`.

The definitive fresh-process L40S run under
`20260715T133606Z-episodes` completed all ten locked records:

- infrastructure acceptance passed, all ten records were valid, every fixture
  proved exactly eight iron cells, and no privileged observer was present;
- zero policy-schema failures and zero safety violations occurred;
- 102 forbidden raw attempts were retained and masked, with none applied;
- episodes 0, 4, 7, 8, and 9 succeeded at steps 120, 141, 105, 59, and 167;
- episodes 1, 2, 3, 5, and 6 ended in valid `timeout_200_steps` failures;
- episode 5 mined one iron block but did not acquire it, so the inventory side
  of the completion oracle correctly remained false; and
- the evaluated score was `5/10`, below the locked `8/10` requirement.

The method took 822.982 seconds after a 17.599-second model load. Across 1,592
native closed-loop steps, the pooled mean was 43.56 ms, but 319 steps (20.04%)
exceeded 50 ms. Native timing was diagnostic rather than a Stage 3 acceptance
condition, so the infrastructure pass does not establish stable 20 Hz
end-to-end control. The result artifact declares fresh-process lifecycle
settings but does not record process IDs as independent per-episode identity
evidence.

This is an evaluated model-competence failure, not an infrastructure failure.
Stage 4 Airicraft shadow mode remains blocked. The next pilot should diagnose
the five locked failure seeds, including the pickup-only failure, before any
Fabric integration or live actuation work.

## Later Live-Pilot Gate

The eventual visible-iron A/B remains blocked until a future native competence
gate passes and Stage 4 shadow-mode checks succeed.
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
