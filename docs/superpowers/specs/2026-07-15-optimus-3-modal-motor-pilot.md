# Optimus-3 Modal Motor Pilot

## Purpose

Determine whether the released Optimus-3 action policy is a viable learned
motor beneath Airicraft's action graph, then validate the first Airicraft
integration without granting the policy control of Minecraft.

The pilot addresses the narrow hypothesis that a neural motor can execute local
movement, aiming, and attack more naturally than Baritone while Airicraft keeps
deterministic ownership of intent, completion, safety, and recovery.

## Architecture Boundary

```text
Airicraft planner
  -> action graph
    -> existing deterministic world-task executor -> Minecraft
    -> frozen task identity + real frames
      -> Optimus-3 recurrent shadow policy
        -> raw action + masked/stabilized shadow action -> evidence only
    <- deterministic completion and failure evidence from the existing runtime
```

The planner remains responsible for high-level intent. The action graph remains
responsible for primitive selection and graph state. During Stage 4, the
existing executor remains the sole actuator and the learned motor's proposed
low-level controls are evidence only. The shadow path has no keybinding,
camera, attack, task-terminal, or graph-completion API. It cannot complete,
fail, replan, transfer, or influence a task.

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
synthetic GPU gates; Stage 3 is a native MineStudio gate. The frozen held-out
Stage 3 v2 result passed at exactly `8/10`, which authorizes Stage 4 shadow
integration only. It does not authorize applying a neural action to Minecraft
or beginning the matched live A/B.

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
  containers, or public endpoint for Stages 1 through 3. Stage 4 alone may
  deploy the strict proxy-authenticated shadow endpoint described below.
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

Stage 3 v2 uses the pinned MineStudio simple iron task source and released
simulator engine. Its ten world/policy seed pairs were preregistered from the
held-out namespace `airicraft-optimus3-stage3-v2-parity-heldout`, distinct from
the v1 schedule used for diagnosis. Every episode starts a fresh Minecraft
process, resets recurrent policy state exactly once, and allows at most 200
scored policy steps. There is no warm-up, fallback, or privileged fixture
observation available to the policy.

The released engine drops the task's selector-relative `fill` command, so the
harness creates the same relative 2x2x2 iron state with absolute `setblock`
actions. V2 restores the pinned MineStudio client runtime, including
`tutorialStep:none`; executes the released GUI reset commands that suppress
command feedback and establish its lighting and time rules; materializes the
fixture; drains 220 unscored no-op ticks; and then revalidates the pickaxe,
position, inventory, and all eight iron blocks. The policy uses the exact
upstream task text `Mine iron ore from the environment.` and the released GUI
five-control attack stabilizer after the safety mask.

Success requires both a positive `mine_block.iron_ore` delta and a positive
inventory `iron_ore` delta relative to the pre-policy baseline. Every scored
episode records ordered source-frame hashes and a same-run visual rendition.
The gate is evaluated only when the exact ten records are present and valid;
all pin, GPU, fixture, schema, safety, and shutdown checks pass; and every
mandatory trace, final-frame, codec, size, and capture-integrity check passes.
`timeout_200_steps` is a valid competence failure. Setup, reset, schema, close,
or capture-integrity failures make the gate unevaluable. An evaluated pass
requires at least eight successes.

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

The historical v1 fresh-process run under
`20260715T133606Z-episodes` completed all ten records but scored `5/10`, below
the locked `8/10` requirement. Episodes 0, 4, 7, 8, and 9 succeeded; episodes
1, 2, 3, 5, and 6 reached valid 200-step timeouts; and episode 5 mined one iron
block without collecting it. All ten records were valid under the declared v1
contract, with zero policy-schema failures or safety violations and 102
forbidden attempts retained and masked.

That v1 failure is preserved as superseded diagnostic evidence. Subsequent
video inspection found that setup command feedback covered much of the policy
input for roughly half of each episode and that discarded MineStudio client
options had re-enabled the tutorial overlay. It therefore was not an
ideal-performance or official-stack estimate and is not the definitive Stage 3
competence result.

The v2 two-process preflight under `20260715T170111Z-sim-preflight` passed all
26 checks, including the pinned runtime template, disabled tutorial, reset
command digest, 220-step policy-view drain, post-drain fixture proof, and exact
five-control attack clamp. A same-run probe under
`20260715T170547Z-episode` used an already-seen v1 seed and succeeded at step
181. That probe established that the contamination was gone but was not a
held-out gate sample.

The definitive frozen held-out v2 run is
`eval-output/optimus3-modal/20260715T173603Z-episodes.kBiA8o/result.json`, run
from commit `29b4a22`. It passed at exactly `8/10`:

- all ten episodes were valid, and every infrastructure and mandatory same-run
  capture check passed;
- schema failures, safety violations, and suite errors were zero;
- episodes 1, 2, 3, 4, 5, 7, 8, and 9 succeeded at steps 84, 77, 58, 92, 100,
  71, 138, and 57;
- episode 0 mined two blocks but collected none, while episode 6 neither mined
  nor collected and moved away from the fixture;
- all ten trace-bound videos were encoded after scoring, with no setup command
  or tutorial overlay found during manual inspection; and
- the full method took 795.420 seconds.

The result artifact SHA-256 is
`0085ea218829f762dae05145017b48887a63eee38244ac9edb3dd271bd0c049d`.
This passes the isolated native-fixture gate and authorizes Stage 4 shadow mode.
It is not an estimate of the official benchmark aggregate, proof of Airicraft
1.21.8 compatibility, or authorization for neural actuation.

## Stage 4 Airicraft Shadow Contract

Stage 4 is authorized but remains unpassed until a real Airicraft shadow run
satisfies this section. Eligibility is deliberately narrow: the session must
be loaded and permitted to act, the task must be owned by the action graph, the
graph must be waiting on a concrete `mine_block` primitive for
`minecraft:raw_iron`, and the block set must contain iron or deepslate iron
ore. The rendered view must remain first-person; a perspective change makes
the identity ineligible, and a tick/render race rejects the captured frame.
One recurrent policy session is bound to one graph/task identity. A
changed identity, cancellation, terminal task state, world leave, reload, or
shutdown stops that shadow session.

The Modal service is an HTTPS Web Function protected by proxy authentication.
Airicraft and the warm-up script refuse to send `Modal-Key` / `Modal-Secret`
outside `https://*.modal.run` (except loopback-only test endpoints); the script
passes those headers through curl configuration input rather than process
arguments.
Its strict protocol is:

- `POST /v1/policy/sessions` creates one recurrent session and resets the policy
  exactly once;
- `POST /v1/policy/sessions/{session-id}/steps` accepts one trace-bound
  `128x128x3` RGB PNG at a strictly increasing step index; and
- `POST /v1/policy/sessions/{session-id}/close` closes the identity-bound
  session and is safe to use after an ambiguous create timeout.

Every request carries contract version `airicraft.optimus3.shadow.v1`, mode
`shadow`, the frozen task and model pins, expected task-embedding and projection
hashes, the exact 22-key policy-action schema, generation and seed, and
`actuationAuthorized: false`. Encoded PNG and decoded RGB hashes bind each step
to its captured frame. There are no automatic retries. An out-of-order step,
changed repeat, pin or shape mismatch, malformed action, decoded-pixel hash
mismatch, timeout, or stale generation fails closed and quarantines that
session.

Airicraft retains the raw 22-key action, locally normalized and forbidden-
control-masked action, released five-control attack stabilization, service
timing, end-to-end timing, frame identity and hashes, deadline result, and any
forbidden attempts as raw-only evidence. Both the raw and safe shadow records
declare that actuation is unauthorized and was not applied. The existing
world-task executor continues independently as the only path allowed to touch
Minecraft controls or graph completion.

The first operator run must:

1. create a Modal Web Function Proxy Token outside Git, start the temporary
   service with `scripts/optimus3-modal shadow-serve`, and use
   `scripts/optimus3-modal shadow-deploy` only after the temporary endpoint is
   verified; before every bounded campaign, call `scripts/optimus3-modal
   shadow-warm` and require a ready, inactive, zero-actuation response after
   the excluded synthetic action-head warm-up;
2. configure the generated HTTPS root URL and proxy-token pair under
   `motor.optimus3Shadow`, retaining the separate cold-start session timeout,
   250 ms step timeout, five-second idempotent-close timeout, and deterministic
   policy seed, then run
   `airicraft reload`;
3. execute one bounded action-graph-owned raw-iron mining job and retain the
   `motor.optimus3_shadow.*` raw events, final motor-shadow status, and
   `motor-shadow-cleanup-final.json`; the cleanup artifact must report
   `cleanupComplete: true`, no request in flight, and the terminal remote-close
   success or failure event must already be present in `events.jsonl`;
4. inject cancellation or an identity change while a step is in flight and
   prove that the stale response is rejected, the deterministic executor is
   unaffected, and no shadow output reaches an actuator; and
5. disable the local shadow configuration and stop or delete the deployed Modal
   App after the campaign.

Evaluation teardown is non-blocking. The recorder continues polling on later
client ticks through the five-second close deadline and writes
`motor-shadow-cleanup-final.json` only after the close reaches a terminal state.
A new evaluator run is rejected with `evaluation_cleanup_in_progress` during
that short post-finish interval so cleanup evidence cannot be attributed to the
wrong campaign.

Stage 4 passes only if frame dimensions and hashes remain exact, every accepted
step belongs to the current graph identity, recurrent reset count is exactly
one, sequence gaps and stale results are rejected, failures quarantine rather
than block the deterministic executor, all forbidden attempts remain evidence
only, and no action, task-terminal, or graph-completion side effect originates
from the shadow path. The run must also record warmed end-to-end latency and
50 ms deadline misses; those measurements decide whether a future active A/B
is technically plausible, but they cannot authorize it by themselves.

## Later Live-Pilot Gate

The held-out native competence prerequisite has passed. The eventual
visible-iron A/B remains blocked until Stage 4 shadow-mode checks succeed and a
separate change explicitly authorizes an exclusive neural actuator path.
The neural arm must achieve at least 80% success and remain within 10 percentage
points of Baritone, have p95 closed-loop latency at or below 50 ms with fewer
than 5% missed deadlines, apply no forbidden action, release control within one
tick on cancellation, and receive at least 65% blinded naturalness preference.

Airicraft's inventory observation remains the source of truth for completion.
The production path retains Baritone fallback, although fallback is disabled
during scored neural trials to preserve attribution.

## Non-Goals

- Replacing the planner, action graph, verifier, or all Baritone execution.
- Applying any Optimus-3 output to Minecraft during Stage 4.
- Running the matched live A/B or implementing an active neural actuator in
  this stage.
- Fine-tuning Optimus-3 or pretraining from gameplay video.
- Claiming Minecraft 1.21.8 compatibility from a synthetic frame.
- Treating one inference latency as evidence of sustained 20 Hz control.
- Claiming official-benchmark parity from the isolated MineStudio fixture.
