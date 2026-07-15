# Optimus-3 Modal Pilot

This experiment establishes the GPU-side prerequisite for an Airicraft learned
motor pilot and screens its recurrent action latency. It does not connect a
neural controller to Minecraft yet.

The first paid smoke proves, in order:

1. a Modal L40S can import the pinned Optimus-3 runtime;
2. the pinned public checkpoints can be cached outside GPU time;
3. the full model can produce one `3584`-dimensional task embedding;
4. the released `0.7B` action head can produce one recurrent action from a
   synthetic `128x128` RGB frame; and
5. forbidden controls are visible in the raw result and zeroed in the applied
   result.

This deliberately uses SDPA for the compatibility and first performance gates.
The benchmark measures a warmed, continuous recurrent stream; it does not claim
Minecraft end-to-end latency.

## Architecture Boundary

The experiment owns model loading and inference only. Airicraft retains the
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

`preflight`, `smoke`, and `bench` request one L40S, at most one container, zero
warm or buffer containers, no configured retries, and a single input per
container. The model class allows at most 10 minutes for startup and 5 minutes
for a method. The benchmark has a stricter internal 240-second wall budget so
it can return partial evidence when that budget is exhausted between steps.
Other runtime failures may still terminate the method without a result.
`cache` uses CPU rather than an L40S and commits roughly 22.2 GB of pinned
assets to the persistent Volume.

These are per-attempt controls, not an absolute spend cap: Modal can reschedule
infrastructure failures independently of configured input retries. Check the
Modal dashboard after each first run.

The Volume persists after an ephemeral App stops. Inspect or delete it with:

```sh
modal volume ls airicraft-optimus3-models
modal volume delete airicraft-optimus3-models
```

Deleting the Volume removes the cached model data and forces a future download.

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

`native_step_ms` includes the stochastic prior, frame preprocessing and device
transfer, classifier-free-guidance recurrent policy, action sampling and
device-to-host mapping, fail-closed validation, normalization, and the safety
mask. It excludes model load, one-time task conditioning, Minecraft frame
capture, transport, tick scheduling, and action application. A pass therefore
authorizes locked native MineStudio episodes; it does not prove competence,
naturalness, Minecraft 1.21.8 compatibility, or live closed-loop performance.

## Upstream Compatibility Notes

The upstream project does not provide a usable dependency lock: its root
dependency list is empty, its `uv.lock` contains only the root package, and its
runtime requirements are mostly unpinned. The top-level agent also loads an
unused task router whose Sentence-BERT path is hard-coded to the authors'
filesystem.

This pilot therefore loads the released full model and action head directly,
matching the official `reset()` embedding path while omitting that unused task
router. A small runtime namespace shim prevents importing the bundled Java
MineStudio simulator merely to access its static action map. The action-head
weights and policy code remain the released versions. The released action
agent's `.to()` helper targets a non-PyTorch wrapper and raises; the pilot uses
its CUDA-aware constructor and verifies each owned module's device instead.

The released per-frame action path also computes a MineCLIP task embedding whose
value is discarded, then repeats the deterministic MLLM projection before its
stochastic prior. The benchmark's cached path removes the discarded lookup and
caches only that deterministic projection. A pinned-source audit indicates this
is distribution-equivalent, but the pilot does not statistically test that
assumption. Removing a stochastic dead call shifts random-number consumption,
so same-seed traces are not expected to be bit-identical.
