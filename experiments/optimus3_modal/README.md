# Optimus-3 Modal Pilot

This experiment establishes the GPU-side prerequisite for an Airicraft learned
motor pilot, screens its recurrent action latency, and measures closed-loop
competence in an isolated native MineStudio simulator. It does not connect a
neural controller to Airicraft's Fabric 1.21.8 actuator path yet.

The Stage 1 paid smoke proves, in order:

1. a Modal L40S can import the pinned Optimus-3 runtime;
2. the pinned public checkpoints can be cached outside GPU time;
3. the full model can produce one `3584`-dimensional task embedding;
4. the released `0.7B` action head can produce one recurrent action from a
   synthetic `128x128` RGB frame; and
5. forbidden controls are visible in the raw result and zeroed in the applied
   result.

Stage 2 deliberately uses SDPA for the performance gate. Its benchmark measures
a warmed, continuous recurrent stream and does not claim Minecraft end-to-end
latency. Stage 3 separately boots the pinned released simulator, proves a native
iron fixture before every episode, and scores ten closed-loop episodes.

## Architecture Boundary

The experiment owns model loading, inference, and its isolated MineStudio
fixture and episode harness. In the production design, Airicraft retains the
planner, action graph, semantic completion, session gate, cancellation, safety
mask, and Baritone fallback. The model cannot declare a graph action complete.

No public web endpoint is created. Every invocation is an authenticated,
ephemeral `modal run`; this experiment must not be run with `--detach` or
`modal deploy`.

Automatic source inclusion is disabled. Modal explicitly mounts only
`modal_app.py` and `contract.py`; it does not mount the Airicraft repository or
any other workspace file. Review those two pilot files before approving a
remote run.

## Pinned Inputs

The full manifest is printed by:

```sh
scripts/optimus3-modal dry-run
```

The important pins are:

- Optimus-3 source: `a73c01365f8091d45e61585aee59b8ef73fb5fb7`
- LLaMA-Factory: `c6c764388cd1fbba7b9ea2d9305093b1b436aa24`
- Full model: `iLearn-Lab/Optimus-3@6168839d8a44c3fab45a31354e683874d14601f3`
- Action head: `MinecraftOptimus/Optimus-3-ActionHead@455e01e30c8e830f420179c93e64f54ce3b30ecc`
- CLIP tokenizer: `openai/clip-vit-base-patch16@57c216476eefef5ab752ec549e440a49ae4ae5f3`
- Simulator engine: `CraftJarvis/SimulatorEngine@48d4809cfddc7e2b85295e8c39b3c5e8c6d46ae7`
  with archive SHA-256
  `293fac6ac72245b3365dce0e8bfbb6396fb94df29b23b6538f3bd7e2eec13ec6`
- Simple iron task config: Git blob
  `97fb7ffa93621ac3aa031bee41a4d2bf55f251e9`, SHA-256
  `49a6396dc868bf902823a4c6db0a71736525fcb3d7baf12b8103461a33af639d`
- Native fixture specification: SHA-256
  `ed7c14283d42e4d1acaf94625ebd7473149664080e4237fb9a0a9b6631519e92`

The v2 checkpoint is intentionally excluded because the published action head
is paired with the preview/full checkpoint, and no v2-specific action head is
published.

## Prerequisites

Use Python 3.11 and install the host-side Modal client in an environment outside
the repository:

```sh
python3.11 -m venv /tmp/airicraft-optimus3-modal
source /tmp/airicraft-optimus3-modal/bin/activate
python -m pip install -r experiments/optimus3_modal/requirements.txt
modal setup
```

`modal profile current` should then print a profile. The checkpoints are public,
so this pilot does not require or create a Hugging Face secret.

## Run Order

Local, non-billable checks:

```sh
scripts/optimus3-modal self-test
scripts/optimus3-modal dry-run
```

Remote image and L40S import preflight:

```sh
scripts/optimus3-modal preflight
```

CPU-side checkpoint download into the persistent
`airicraft-optimus3-models` Volume:

```sh
scripts/optimus3-modal cache
```

One L40S model/action smoke:

```sh
scripts/optimus3-modal smoke --task "collect one iron ore" --seed 7
```

One L40S recurrent latency benchmark:

```sh
scripts/optimus3-modal bench \
  --task "collect one iron ore" \
  --seed 7 \
  --reference-steps 8 \
  --warmup-steps 32 \
  --measured-steps 256
```

CPU-side simulator download into the persistent
`airicraft-optimus3-simulator` Volume, followed by a native lifecycle preflight:

```sh
scripts/optimus3-modal engine-cache
scripts/optimus3-modal sim-preflight
```

One fixed-seed native episode, then the locked ten-episode gate:

```sh
scripts/optimus3-modal episode
scripts/optimus3-modal episodes
```

The benchmark first samples eight exact released `optimus3_action` calls as an
unadjusted diagnostic. It then independently resets and reseeds the action
policy, runs 32 warm-up steps, and measures the next 256 steps without resetting
recurrent state. The deterministic `3584 -> 512` task projection is computed
once. The released stochastic prior remains inside every action step; caching
its sampled output would change the policy. Because the reference calls are not
warm-state matched, their mean-latency ratio is not an acceptance metric or a
controlled speedup claim.

Each command writes a manifest and result under
`eval-output/optimus3-modal/<UTC timestamp>/`. That directory is already
ignored by Git.

## Cost and Lifetime Controls

GPU work requests one L40S, at most one container, zero warm or buffer
containers, no configured retries, and a single input per container. GPU import
preflight allows 10 minutes for startup and 3 minutes for the method. Model and
native-episode work allows 15 minutes for startup and has a 30-minute hard
method timeout. The latency benchmark has a stricter internal 240-second wall
budget; a single native episode has 10 minutes; the ten-episode suite has 25
minutes. These cooperative budgets are checked between blocking model and
simulator calls; the Modal method timeout remains the hard stop for a hung call.

`cache` and `engine-cache` use CPU rather than an L40S and allow two hours for
large immutable downloads. `sim-preflight` uses four CPU cores, Mesa/Xvfb, and
a 15-minute method timeout. The model cache contains roughly 22.2 GB of pinned
assets. The simulator cache contains the verified released engine archive and
fat jar.

These are per-attempt controls, not an absolute spend cap: Modal can reschedule
infrastructure failures independently of configured input retries. Check the
Modal dashboard after each first run.

The Volume persists after an ephemeral App stops. Inspect or delete it with:

```sh
modal volume ls airicraft-optimus3-models
modal volume delete airicraft-optimus3-models
modal volume ls airicraft-optimus3-simulator
modal volume delete airicraft-optimus3-simulator
```

Deleting either Volume removes its cached data and forces a future download.

## Expected Smoke Result

The result must contain:

- `kind: model_action_smoke`;
- action label `<iron>` for the default task;
- embedding shape `[1, 1, 3584]` with finite values;
- a SHA-256 digest of the embedding;
- raw and safety-masked action dictionaries;
- model-load, task-embedding, and one-action timing; and
- CUDA allocation and peak-allocation evidence.

Passing this smoke means only that the released artifacts are executable on the
selected GPU. It does not establish Minecraft 1.21.8 compatibility, 20 Hz
latency, mining competence, safety, or naturalness.

## Expected Benchmark Result

The benchmark result has `kind: model_action_latency_benchmark` and retains the
full raw and safety-masked action evidence for every step. It reports nearest-
rank p50, p90, p95, and p99 latency, mean-derived throughput, 50 ms deadline
misses, CUDA memory, the one-time embedding and projection costs, and an
explicitly unadjusted released-path diagnostic.

The latency screen passes only when all requested phases finish, at least 200
continuous measured steps are present, p95 native-step latency is at most 50 ms,
strictly fewer than 5% of steps exceed 50 ms, every raw action has the complete
expected schema, and the safety mask has no violation. A forbidden policy
attempt is retained as evidence and is not itself a mask failure.

The pinned Optimus policy emits 22 controls: movement, camera, attack, use,
inventory, drop, escape, and nine hotbar choices. `pickItem` and `swapHands` are
Airicraft actuator controls, not Optimus outputs. Fail-closed validation requires
the exact 22-key policy schema; normalization then adds those two actuator-only
controls as zero before the safety mask produces the 24-key applied action.

`native_step_ms` includes the stochastic prior, frame preprocessing and device
transfer, classifier-free-guidance recurrent policy, action sampling and
device-to-host mapping, fail-closed validation, normalization, and the safety
mask. It excludes model load, one-time task conditioning, Minecraft frame
capture, transport, tick scheduling, and action application. A pass therefore
authorizes locked native MineStudio episodes; it does not prove competence,
naturalness, Minecraft 1.21.8 compatibility, or live closed-loop performance.

## Native Stage 3 Contract and Result

The locked gate runs exactly ten fixed world/policy seed pairs, resets recurrent
policy state once per episode, and starts a fresh Minecraft process for every
episode. Each episode allows at most 200 scored policy steps, with no warm-up,
fallback, video recording, or privileged fixture observation available to the
policy. Success requires both `mine_block.iron_ore >= 1` and inventory
`iron_ore >= 1` relative to the pre-policy baseline.

The gate is evaluated only when all ten requested episodes are present and
valid and every infrastructure, pin, fixture, schema, and safety check passes.
It passes at eight or more successes. `timeout_200_steps` is a valid competence
failure; setup, reset, schema, or close failures leave the gate unevaluated.

The definitive fresh-process run under `20260715T133606Z-episodes` was valid but
failed the competence gate:

- all ten episodes were valid, with zero schema failures and zero safety
  violations;
- all ten fixtures contained exactly eight proven iron cells and no privileged
  observer;
- episodes 0, 4, 7, 8, and 9 succeeded at steps 120, 141, 105, 59, and 167;
- episodes 1, 2, 3, 5, and 6 reached the 200-step timeout;
- episode 5 mined one iron block but did not pick it up, so the conjunctive
  completion oracle correctly rejected it;
- all 102 forbidden raw attempts were retained and masked, with none applied;
- the final score was `5/10`, below the locked `8/10` requirement; and
- the method took 822.982 seconds after a 17.599-second model load on an NVIDIA
  L40S.

Infrastructure acceptance passed, so this is an evaluated model-competence
failure rather than an invalid benchmark. It does not authorize Stage 4
Airicraft shadow mode. Across 1,592 native closed-loop steps, the pooled mean was
43.56 ms but 319 steps (20.04%) exceeded 50 ms. Native timing was diagnostic,
not part of this Stage 3 acceptance gate, and does not establish stable 20 Hz
end-to-end control.

The preceding single-episode run under `20260715T131739Z-episode` succeeded at
step 114 and proved that the released policy can complete the native task. The
first suite under `20260715T132001Z-episodes` reused one Minecraft JVM and became
invalid when its seventh reset timed out; its partial `2/6` score is diagnostic
only. The replacement lifecycle passed 19 CPU checks across two fresh processes
under `20260715T133227Z-sim-preflight` before the definitive suite was run. The
definitive artifact declares fresh-process lifecycle settings but does not
record process IDs as independent per-episode identity evidence.

## Upstream Compatibility Notes

The upstream project does not provide a usable dependency lock: its root
dependency list is empty, its `uv.lock` contains only the root package, and its
runtime requirements are mostly unpinned. The top-level agent also loads an
unused task router whose Sentence-BERT path is hard-coded to the authors'
filesystem.

This pilot therefore loads the released full model and action head directly,
matching the official `reset()` embedding path while omitting that unused task
router. For synthetic Stages 1 and 2, a small runtime namespace shim avoids
booting the bundled Java simulator merely to access its static action map.
Stage 3 intentionally boots the pinned released simulator. The action-head
weights and policy code remain the released versions. The released action
agent's `.to()` helper targets a non-PyTorch wrapper and raises; the pilot uses
its CUDA-aware constructor and verifies each owned module's device instead.

The released simulator engine does not apply the task config's selector-relative
`fill` command. The harness therefore reproduces the same 2x2x2 initial iron
state with one absolute `setblock` action per cell, settles two no-op ticks, and
queries the target volume with the released engine's half-open voxel bounds
`(2, 4, 0, 2, 2, 4)`. A bounded second pass normally resends the one cell the
engine misses on the first pass; at most three passes are allowed. Policy
inference starts only after all eight cells are proven. This is a
semantic-equivalent fixture reproduction, not upstream-command parity. The
locked policy prompt remains `collect one iron ore` for Stage 2 conditioning
continuity, so the result is not upstream-prompt parity or a visible-iron test.

The released per-frame action path also computes a MineCLIP task embedding whose
value is discarded, then repeats the deterministic MLLM projection before its
stochastic prior. The benchmark's cached path removes the discarded lookup and
caches only that deterministic projection. A pinned-source audit indicates this
is distribution-equivalent, but the pilot does not statistically test that
assumption. Removing a stochastic dead call shifts random-number consumption,
so same-seed traces are not expected to be bit-identical.
