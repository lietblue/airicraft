#!/usr/bin/env python3
"""Pure, locally testable contract for the Optimus-3 Modal pilot."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA_VERSION = 2
APP_NAME = "airicraft-optimus3-pilot"
VOLUME_NAME = "airicraft-optimus3-models"
SIMULATOR_VOLUME_NAME = "airicraft-optimus3-simulator"
GPU_TYPE = "L40S"
DEFAULT_TASK = "collect one iron ore"
FRAME_SHAPE = (128, 128, 3)
DEFAULT_REFERENCE_STEPS = 8
DEFAULT_WARMUP_STEPS = 32
DEFAULT_MEASURED_STEPS = 256
MIN_MEASURED_STEPS = 200
MAX_MEASURED_STEPS = 512
MAX_WARMUP_STEPS = 256
MAX_REFERENCE_STEPS = 32
MAX_TOTAL_BENCHMARK_STEPS = 600
TARGET_LATENCY_MS = 50.0
MAX_DEADLINE_MISS_RATE = 0.05
BENCHMARK_WALL_BUDGET_SECONDS = 240.0

LEGACY_NATIVE_GATE_NAME = "native_simple_mine_iron_v1"
NATIVE_GATE_NAME = "native_simple_mine_iron_v2_parity"
NATIVE_EPISODE_COUNT = 10
NATIVE_REQUIRED_SUCCESSES = 8
NATIVE_MAX_STEPS = 200
NATIVE_SUITE_WALL_BUDGET_SECONDS = 25 * 60.0
NATIVE_TASK_CONFIG = "MineStudio/minestudio/benchmark/task_configs/simple/mine_iron_ore.yaml"
NATIVE_TASK_CONFIG_GIT_BLOB = "97fb7ffa93621ac3aa031bee41a4d2bf55f251e9"
NATIVE_TASK_CONFIG_SHA256 = "49a6396dc868bf902823a4c6db0a71736525fcb3d7baf12b8103461a33af639d"
NATIVE_UPSTREAM_TASK_TEXT = "Mine iron ore from the environment."
NATIVE_POLICY_PROMPT = NATIVE_UPSTREAM_TASK_TEXT
NATIVE_UPSTREAM_TASK_COMMANDS = (
    "/replaceitem entity @s weapon.mainhand minecraft:stone_pickaxe",
    "/execute as @p at @s run fill ~2 ~ ~2 ~3 ~1 ~3 minecraft:iron_ore",
)
NATIVE_UPSTREAM_TASK_COMMANDS_SHA256 = "b0a5ad53ce0b70b7a62ae16543b9d68b099b874a9283cfb59b21448b51fa91a6"
NATIVE_ENVIRONMENT_COMMANDS = (
    "/gamerule sendCommandFeedback false",
    "/gamerule commandBlockOutput false",
    "/gamerule keepInventory true",
    "/effect give @a night_vision 99999 250 true",
    "/gamerule doDaylightCycle false",
    "/time set 0",
    "/gamerule doImmediateRespawn true",
    "/spawnpoint",
)
NATIVE_ENVIRONMENT_COMMANDS_SHA256 = "c7feb48511e37e86037a6f97a500e08058324135298ae6d692df17b0efcb6ed9"
NATIVE_POLICY_VIEW_SETTLE_STEPS = 220
NATIVE_RUNTIME_TEMPLATE_SHA256 = {
    "options.txt": "f55c7a867d5ea1309cf13e3def7a25a71ac73f5948f2d5580af3905210644a6f",
    "optionsof.txt": "c4d755753b6b4dcd7137119895b778de1aef941b87f246418a6c845e1be557ee",
}
NATIVE_FIXTURE_METHOD = "absolute_setblocks_with_half_open_voxel_oracle"
NATIVE_FIXTURE_BLOCK_OFFSETS = tuple(
    (x, y, z)
    for x in (2, 3)
    for y in (0, 1)
    for z in (2, 3)
)
NATIVE_FIXTURE_MAX_MATERIALIZATION_PASSES = 3
NATIVE_FIXTURE_SETTLE_STEPS = 2
NATIVE_FIXTURE_VOXEL_BOUNDS = (2, 4, 0, 2, 2, 4)
NATIVE_FIXTURE_SPEC_SHA256 = "ed7c14283d42e4d1acaf94625ebd7473149664080e4237fb9a0a9b6631519e92"
NATIVE_EXPECTED_IRON_BLOCKS = 8
NATIVE_EMBEDDING_SEED = 7
NATIVE_EXPECTED_LABEL = "<iron>"
NATIVE_EXPECTED_EMBEDDING_SHA256 = "6ad1d2ed8fc13474afef66fafd96ef24b622ae880fffc24d9825c16c3101f4c3"
NATIVE_EXPECTED_PROJECTION_SHA256 = "b9d7cb0abcc9f5f0192f28212f4894485a6770d523a7e4bd718bdfea12a687e3"
LEGACY_NATIVE_SEED_NAMESPACE = "airicraft-optimus3-stage3-v1"
NATIVE_SEED_NAMESPACE = "airicraft-optimus3-stage3-v2-parity-heldout"
NATIVE_FAILURE_REPLAY_INDICES = (1, 2, 3, 5, 6)
NATIVE_FAILURE_REPLAY_SOURCE_RESULT = "eval-output/optimus3-modal/20260715T133606Z-episodes/result.json"
NATIVE_FAILURE_REPLAY_SOURCE_SHA256 = "ea6b38e87b95c7c471797e5e72b73b772c0ed10d79508a6de8e998386ee62302"
NATIVE_FAILURE_REPLAY_TRACE_SHA256 = {
    1: "2cf5d0d863a52eaff116c1a3dfb8a208f30bc7e580d2a780bee36c77d732c8ae",
    2: "c4e9b2b51ab5f10fbca82e2a61e719c7cdb641fa1f9c35fb2f082bfa82dc512d",
    3: "bbca04d885f4d7e778fa8a6393ce950afa6f0714a1e2b4412d21ecd8c89c5c3e",
    5: "b77b74a1e1e7056f2c9a1d78c84fb1c0938db82f619e8c948c864234b69ff8ee",
    6: "2a95ca42e4d278c2e400a60c1ea96ecbe0500cab7afd11566261badbdd8874dc",
}
NATIVE_FAILURE_REPLAY_ACTION_SHA256 = {
    1: "b373d6e92fa181f84795b0da0b65be52718083332e79f1e5919a589a10f09ab1",
    2: "8358c3dff92d89d3e4be55da503cacdd048c814386f64c0a2b5abf3e7a61188e",
    3: "85390a0d291762364ec892c1523f44bd6226412c7e4afd15a83629ac2732f17f",
    5: "2f6168246008c21edb8b925a6bc03b841d57526b06384a943c03280198e6c90e",
    6: "2bde48dc038bcd299ea6f9296f5647cb47c0e293479737eff3b27ccd90e5bb3b",
}
NATIVE_FAILURE_REPLAY_VIDEO_FPS = 20
NATIVE_FAILURE_REPLAY_MAX_TOTAL_BYTES = 32 * 1024 * 1024
NATIVE_GATE_VIDEO_FPS = 20
NATIVE_GATE_MAX_TOTAL_VIDEO_BYTES = 32 * 1024 * 1024
NATIVE_CAPTURE_WALL_BUDGET_SECONDS = 3 * 60.0
NATIVE_MODAL_METHOD_TIMEOUT_SECONDS = 35 * 60
_SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def native_fixture_spec() -> dict[str, Any]:
    return {
        "block_offsets": [list(offset) for offset in NATIVE_FIXTURE_BLOCK_OFFSETS],
        "command_transport": "MineStudio execute_cmd absolute setblock actions",
        "expected_iron_blocks": NATIVE_EXPECTED_IRON_BLOCKS,
        "inventory": {"0": {"quantity": 1, "type": "stone_pickaxe"}},
        "materialization": "one absolute setblock command per expected block",
        "maximum_materialization_passes": NATIVE_FIXTURE_MAX_MATERIALIZATION_PASSES,
        "placement": "discovery_location",
        "settle_noop_steps": NATIVE_FIXTURE_SETTLE_STEPS,
        "voxel_bounds_half_open": list(NATIVE_FIXTURE_VOXEL_BOUNDS),
    }


def native_environment_spec() -> dict[str, Any]:
    return {
        "commands": list(NATIVE_ENVIRONMENT_COMMANDS),
        "commands_sha256": NATIVE_ENVIRONMENT_COMMANDS_SHA256,
        "policy_view_settle_noop_steps": NATIVE_POLICY_VIEW_SETTLE_STEPS,
        "runtime_template_sha256": dict(NATIVE_RUNTIME_TEMPLATE_SHA256),
        "purpose": "match the released GUI reset and allow transient setup HUD to clear",
    }


SIMULATOR_ENGINE_REPOSITORY = "CraftJarvis/SimulatorEngine"
SIMULATOR_ENGINE_REVISION = "48d4809cfddc7e2b85295e8c39b3c5e8c6d46ae7"
SIMULATOR_ENGINE_FILENAME = "engine.zip"
SIMULATOR_ENGINE_EXPECTED_BYTES = 458_106_630
SIMULATOR_ENGINE_SHA256 = "293fac6ac72245b3365dce0e8bfbb6396fb94df29b23b6538f3bd7e2eec13ec6"
SIMULATOR_ENGINE_JAR = "engine/build/libs/mcprec-6.13.jar"


def _native_seed(namespace: str, stream: str, index: int) -> int:
    digest = hashlib.sha256(f"{namespace}:{stream}:{index}".encode("utf-8")).digest()
    return int.from_bytes(digest[:4], byteorder="big", signed=False)


LEGACY_NATIVE_WORLD_SEEDS = tuple(
    _native_seed(LEGACY_NATIVE_SEED_NAMESPACE, "world", index)
    for index in range(NATIVE_EPISODE_COUNT)
)
LEGACY_NATIVE_POLICY_SEEDS = tuple(
    _native_seed(LEGACY_NATIVE_SEED_NAMESPACE, "policy", index)
    for index in range(NATIVE_EPISODE_COUNT)
)
NATIVE_WORLD_SEEDS = tuple(
    _native_seed(NATIVE_SEED_NAMESPACE, "world", index) for index in range(NATIVE_EPISODE_COUNT)
)
NATIVE_POLICY_SEEDS = tuple(
    _native_seed(NATIVE_SEED_NAMESPACE, "policy", index) for index in range(NATIVE_EPISODE_COUNT)
)

OPTIMUS3_REPOSITORY = "https://github.com/JiuTian-VL/Optimus-3.git"
OPTIMUS3_REVISION = "a73c01365f8091d45e61585aee59b8ef73fb5fb7"
LLAMA_FACTORY_REPOSITORY = "https://github.com/hiyouga/LLaMA-Factory.git"
LLAMA_FACTORY_REVISION = "c6c764388cd1fbba7b9ea2d9305093b1b436aa24"

RUNTIME_PINS = {
    "python": "3.11",
    "cuda": "12.4",
    "torch": "2.6.0+cu124",
    "torchvision": "0.21.0+cu124",
    "transformers": "4.51.3",
    "tokenizers": "0.21.1",
    "datasets": "3.6.0",
    "peft": "0.15.2",
    "trl": "0.9.6",
    "tyro": "0.8.14",
    "qwen_vl_utils": "0.0.11",
    "attention_implementation": "sdpa",
}

SIMULATOR_RUNTIME_PINS = {
    "java": "8",
    "renderer": "xvfb_cpu_mesa",
    "subprocess_callback_imports": "sitecustomize_minecraft_callback_only",
    "gymnasium": "0.29.1",
    "pyro4": "4.82",
    "psutil": "7.0.0",
    "diskcache": "5.6.3",
    "lxml": "5.4.0",
    "xmltodict": "0.14.2",
    "coloredlogs": "15.0.1",
    "daemoniker": "0.2.3",
    "cuda_python": "12.4.0",
    "rich": "14.0.0",
    "pyyaml": "6.0.2",
    "absl_py": "2.2.2",
    "jinja2": "3.1.6",
}

ALLOWED_ACTION_KEYS = (
    "attack",
    "back",
    "camera",
    "forward",
    "jump",
    "left",
    "right",
    "sneak",
    "sprint",
)
OFFICIAL_ATTACK_STABILIZED_KEYS = (
    "jump",
    "left",
    "right",
    "sneak",
    "sprint",
)
FORBIDDEN_ACTION_KEYS = (
    "ESC",
    "drop",
    "hotbar.1",
    "hotbar.2",
    "hotbar.3",
    "hotbar.4",
    "hotbar.5",
    "hotbar.6",
    "hotbar.7",
    "hotbar.8",
    "hotbar.9",
    "inventory",
    "pickItem",
    "swapHands",
    "use",
)
ALL_ACTION_KEYS = ALLOWED_ACTION_KEYS + FORBIDDEN_ACTION_KEYS
ACTUATOR_ONLY_ACTION_KEYS = ("pickItem", "swapHands")
POLICY_ACTION_KEYS = tuple(key for key in ALL_ACTION_KEYS if key not in ACTUATOR_ONLY_ACTION_KEYS)
ACTION_LABELS = (
    "<dirt>",
    "<tree>",
    "<diamond>",
    "<gold>",
    "<iron>",
    "<seed>",
    "<cobblestone>",
    "<house>",
    "<craft>",
    "<redstone>",
)


@dataclass(frozen=True)
class ModelSpec:
    key: str
    repo_id: str
    revision: str
    relative_path: str
    expected_bytes: int
    required_files: tuple[str, ...]
    allow_patterns: tuple[str, ...] | None = None


MODEL_SPECS = (
    ModelSpec(
        key="mllm",
        repo_id="iLearn-Lab/Optimus-3",
        revision="6168839d8a44c3fab45a31354e683874d14601f3",
        relative_path="optimus3/6168839d8a44c3fab45a31354e683874d14601f3",
        expected_bytes=19_454_254_727,
        required_files=("config.json", "model.safetensors.index.json", "tokenizer.json"),
    ),
    ModelSpec(
        key="action_head",
        repo_id="MinecraftOptimus/Optimus-3-ActionHead",
        revision="455e01e30c8e830f420179c93e64f54ce3b30ecc",
        relative_path="optimus3-action/455e01e30c8e830f420179c93e64f54ce3b30ecc",
        expected_bytes=2_702_437_083,
        required_files=("config.json", "model.safetensors"),
    ),
    ModelSpec(
        key="clip_tokenizer",
        repo_id="openai/clip-vit-base-patch16",
        revision="57c216476eefef5ab752ec549e440a49ae4ae5f3",
        relative_path="clip-tokenizer/57c216476eefef5ab752ec549e440a49ae4ae5f3",
        expected_bytes=3_715_163,
        required_files=("tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt"),
        allow_patterns=(
            "config.json",
            "merges.txt",
            "special_tokens_map.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "vocab.json",
        ),
    ),
)


def model_spec(key: str) -> ModelSpec:
    for spec in MODEL_SPECS:
        if spec.key == key:
            return spec
    raise KeyError(f"unknown model key: {key}")


def validate_task(task: str) -> str:
    if not isinstance(task, str):
        raise TypeError("task must be a string")
    normalized = " ".join(task.split())
    if not normalized:
        raise ValueError("task must not be empty")
    if len(normalized) > 256:
        raise ValueError("task must be at most 256 characters")
    if any(ord(character) < 32 for character in task):
        raise ValueError("task must not contain control characters")
    return normalized


def validate_seed(seed: int) -> int:
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise TypeError("seed must be an integer")
    if seed < 0 or seed > 2**32 - 1:
        raise ValueError("seed must be between 0 and 2^32 - 1")
    return seed


def validate_episode_index(index: int) -> int:
    if isinstance(index, bool) or not isinstance(index, int):
        raise TypeError("episode index must be an integer")
    if index < 0 or index >= NATIVE_EPISODE_COUNT:
        raise ValueError(f"episode index must be between 0 and {NATIVE_EPISODE_COUNT - 1}")
    return index


def native_episode_seeds(index: int) -> tuple[int, int]:
    index = validate_episode_index(index)
    return NATIVE_WORLD_SEEDS[index], NATIVE_POLICY_SEEDS[index]


def native_diagnostic_episode_seeds(index: int) -> tuple[int, int]:
    index = validate_episode_index(index)
    return LEGACY_NATIVE_WORLD_SEEDS[index], LEGACY_NATIVE_POLICY_SEEDS[index]


def _count_value(value: Any) -> float:
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"counter value must be numeric, got {type(value).__name__}")
    normalized = float(value)
    if not math.isfinite(normalized) or normalized < 0:
        raise ValueError("counter value must be finite and non-negative")
    return normalized


def stat_count(stats: Any, item: str) -> float:
    if stats is None:
        return 0.0
    if not isinstance(stats, Mapping):
        raise TypeError("stats must be a mapping")
    return _count_value(stats.get(item, 0))


def inventory_quantity(inventory: Any, item: str) -> float:
    if inventory is None:
        return 0.0
    if not isinstance(inventory, Mapping):
        raise TypeError("inventory must be a mapping")
    total = 0.0
    for slot in inventory.values():
        if not isinstance(slot, Mapping):
            raise TypeError("inventory slots must be mappings")
        item_type = str(slot.get("type", "")).removeprefix("minecraft:")
        if item_type == item:
            total += _count_value(slot.get("quantity", 0))
    return total


def native_episode_success(info: Mapping[str, Any], baseline: Mapping[str, float]) -> bool:
    inventory_delta = inventory_quantity(info.get("inventory"), "iron_ore") - float(
        baseline["inventory_iron_ore"]
    )
    mined_delta = stat_count(info.get("mine_block"), "iron_ore") - float(baseline["mine_iron_ore"])
    return inventory_delta >= 1 and mined_delta >= 1


def applied_action_sequence_sha256(trace: Sequence[Mapping[str, Any]]) -> str:
    actions: list[dict[str, Any]] = []
    for record in trace:
        if "applied_action" not in record:
            continue
        action = record["applied_action"]
        if not isinstance(action, Mapping):
            raise TypeError("applied action must be a mapping")
        actions.append(to_jsonable(action))
    if not actions:
        raise ValueError("trace must contain at least one applied action")
    payload = (json.dumps(actions, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def capture_frame_sequence_evidence(
    frame_sha256s: Sequence[str],
    trace: Sequence[Mapping[str, Any]],
    final_frame_sha256: str,
    policy_steps: int,
) -> dict[str, Any]:
    if isinstance(policy_steps, bool) or not isinstance(policy_steps, int):
        raise TypeError("policy_steps must be an integer")
    if policy_steps < 0:
        raise ValueError("policy_steps must be non-negative")

    normalized_frame_sha256s = list(frame_sha256s)
    if not normalized_frame_sha256s:
        raise ValueError("capture must contain at least one source frame digest")
    if not all(
        isinstance(digest, str) and _SHA256_PATTERN.fullmatch(digest)
        for digest in normalized_frame_sha256s
    ):
        raise ValueError("source frame digests must be lowercase SHA-256 values")
    if not isinstance(final_frame_sha256, str) or not _SHA256_PATTERN.fullmatch(
        final_frame_sha256
    ):
        raise ValueError("final frame digest must be a lowercase SHA-256 value")

    trace_input_sha256s: list[str] = []
    for record in trace:
        if "applied_action" not in record:
            continue
        digest = record.get("input_frame_sha256")
        if not isinstance(digest, str) or not _SHA256_PATTERN.fullmatch(digest):
            raise ValueError("every applied-action trace record must have an input frame digest")
        trace_input_sha256s.append(digest)

    def sequence_digest(values: Sequence[str]) -> str:
        payload = (json.dumps(list(values), separators=(",", ":")) + "\n").encode("utf-8")
        return hashlib.sha256(payload).hexdigest()

    return {
        "source_frame_sha256s": normalized_frame_sha256s,
        "source_frame_sequence_sha256": sequence_digest(normalized_frame_sha256s),
        "trace_input_frame_sha256s": trace_input_sha256s,
        "trace_input_frame_sequence_sha256": sequence_digest(trace_input_sha256s),
        "source_frame_count": len(normalized_frame_sha256s),
        "trace_input_frame_count": len(trace_input_sha256s),
        "frame_count_matches_policy_steps": len(normalized_frame_sha256s) == policy_steps + 1,
        "source_frames_match_trace_inputs": (
            normalized_frame_sha256s[:-1] == trace_input_sha256s
        ),
        "final_source_frame_matches_episode": (
            normalized_frame_sha256s[-1] == final_frame_sha256
        ),
    }


def native_capture_acceptance_checks(
    episode_indices: Sequence[int],
    episodes: Sequence[Mapping[str, Any]],
    captures: Sequence[Mapping[str, Any]],
    capture_file_count: int,
    total_capture_bytes: int,
) -> dict[str, bool]:
    for name, value in (
        ("capture_file_count", capture_file_count),
        ("total_capture_bytes", total_capture_bytes),
    ):
        if isinstance(value, bool) or not isinstance(value, int):
            raise TypeError(f"{name} must be an integer")
        if value < 0:
            raise ValueError(f"{name} must be non-negative")

    requested = list(episode_indices)
    capture_records = list(captures)
    return {
        "exact_requested_episode_schedule": [
            capture.get("episode_index") for capture in capture_records
        ]
        == requested,
        "all_requested_episodes_completed": len(episodes) == len(requested),
        "all_capture_records_present": len(capture_records) == len(requested),
        "capture_errors_absent": all(
            "capture_error" not in capture for capture in capture_records
        ),
        "all_simulator_closes_succeeded": all(
            capture.get("simulator_close_succeeded") is True for capture in capture_records
        ),
        "all_videos_encoded": (
            capture_file_count == len(requested)
            and all(capture.get("video") is not None for capture in capture_records)
        ),
        "all_frame_counts_match": all(
            isinstance(capture.get("video"), Mapping)
            and capture["video"].get("frame_count") == capture.get("policy_steps", 0) + 1
            and capture.get("frame_count_matches_policy_steps") is True
            for capture in capture_records
        ),
        "all_video_metadata_valid": all(
            isinstance(capture.get("video"), Mapping)
            and isinstance(capture["video"].get("checks"), Mapping)
            and all(capture["video"]["checks"].values())
            for capture in capture_records
        ),
        "all_trace_digests_recorded": all(
            isinstance(capture.get("authoritative_trace_sha256"), str)
            and bool(_SHA256_PATTERN.fullmatch(capture["authoritative_trace_sha256"]))
            for capture in capture_records
        ),
        "all_action_digests_recorded": all(
            isinstance(capture.get("applied_action_sha256"), str)
            and bool(_SHA256_PATTERN.fullmatch(capture["applied_action_sha256"]))
            for capture in capture_records
        ),
        "all_source_frames_match_trace": all(
            capture.get("source_frames_match_trace_inputs") is True
            for capture in capture_records
        ),
        "all_final_frames_match_episode": all(
            capture.get("final_source_frame_matches_episode") is True
            for capture in capture_records
        ),
        "all_source_frame_digests_recorded": all(
            isinstance(capture.get("source_frame_sha256s"), list)
            and len(capture["source_frame_sha256s"]) == capture.get("policy_steps", 0) + 1
            and isinstance(capture.get("source_frame_sequence_sha256"), str)
            and bool(_SHA256_PATTERN.fullmatch(capture["source_frame_sequence_sha256"]))
            for capture in capture_records
        ),
        "total_video_bytes_within_limit": (
            0 < total_capture_bytes <= NATIVE_GATE_MAX_TOTAL_VIDEO_BYTES
        ),
    }


def native_suite_outcome(
    episodes: Sequence[Mapping[str, Any]],
    infrastructure_passed: bool,
) -> dict[str, Any]:
    if not isinstance(infrastructure_passed, bool):
        raise TypeError("infrastructure_passed must be a boolean")
    expected_indices = list(range(NATIVE_EPISODE_COUNT))
    observed_indices = [episode.get("episode_index") for episode in episodes]
    exact_schedule = observed_indices == expected_indices
    valid_episodes = sum(bool(episode.get("valid")) for episode in episodes)
    successes = sum(
        bool(episode.get("valid")) and bool(episode.get("success")) for episode in episodes
    )
    complete_and_valid = len(episodes) == NATIVE_EPISODE_COUNT and valid_episodes == NATIVE_EPISODE_COUNT
    evaluated = infrastructure_passed and exact_schedule and complete_and_valid
    return {
        "evaluated": evaluated,
        "passed": successes >= NATIVE_REQUIRED_SUCCESSES if evaluated else None,
        "exact_locked_episode_schedule": exact_schedule,
        "complete_and_valid": complete_and_valid,
        "valid_episodes": valid_episodes,
        "successes": successes,
        "required_successes": NATIVE_REQUIRED_SUCCESSES,
    }


def native_gate_acceptance(
    suite_outcome: Mapping[str, Any],
    gate_requested: bool,
    capture_integrity_passed: bool,
) -> dict[str, bool | None]:
    for name, value in (
        ("gate_requested", gate_requested),
        ("capture_integrity_passed", capture_integrity_passed),
    ):
        if not isinstance(value, bool):
            raise TypeError(f"{name} must be a boolean")
    suite_evaluated = suite_outcome.get("evaluated") is True
    capture_requirement_met = not gate_requested or capture_integrity_passed
    evaluated = suite_evaluated and capture_requirement_met
    return {
        "evaluated": evaluated,
        "passed": suite_outcome.get("passed") if evaluated else None,
        "capture_requirement_met": capture_requirement_met,
    }


def validate_benchmark_steps(
    reference_steps: int,
    warmup_steps: int,
    measured_steps: int,
) -> tuple[int, int, int]:
    values = {
        "reference_steps": (reference_steps, 1, MAX_REFERENCE_STEPS),
        "warmup_steps": (warmup_steps, 1, MAX_WARMUP_STEPS),
        "measured_steps": (measured_steps, MIN_MEASURED_STEPS, MAX_MEASURED_STEPS),
    }
    for name, (value, minimum, maximum) in values.items():
        if isinstance(value, bool) or not isinstance(value, int):
            raise TypeError(f"{name} must be an integer")
        if value < minimum or value > maximum:
            raise ValueError(f"{name} must be between {minimum} and {maximum}")
    if reference_steps + warmup_steps + measured_steps > MAX_TOTAL_BENCHMARK_STEPS:
        raise ValueError(f"total benchmark steps must not exceed {MAX_TOTAL_BENCHMARK_STEPS}")
    return reference_steps, warmup_steps, measured_steps


def validate_frame_shape(frame: Any) -> tuple[int, int, int]:
    shape = getattr(frame, "shape", None)
    if shape is None and isinstance(frame, Sequence):
        shape = _nested_shape(frame)
    normalized = tuple(int(dimension) for dimension in shape) if shape is not None else ()
    if normalized != FRAME_SHAPE:
        raise ValueError(f"frame shape must be {FRAME_SHAPE}, got {normalized or 'unknown'}")
    return FRAME_SHAPE


def _nested_shape(value: Sequence[Any]) -> tuple[int, ...]:
    shape: list[int] = []
    current: Any = value
    while isinstance(current, Sequence) and not isinstance(current, (str, bytes, bytearray)):
        shape.append(len(current))
        if not current:
            break
        current = current[0]
    return tuple(shape)


def to_jsonable(value: Any) -> Any:
    if hasattr(value, "detach"):
        value = value.detach()
    if hasattr(value, "cpu"):
        value = value.cpu()
    if hasattr(value, "tolist"):
        value = value.tolist()
    elif hasattr(value, "item"):
        value = value.item()
    if isinstance(value, Mapping):
        return {str(key): to_jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(item) for item in value]
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite numeric action value")
        return value
    if isinstance(value, (str, int, bool)) or value is None:
        return value
    raise TypeError(f"unsupported result value: {type(value).__name__}")


def normalize_action(action: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(action, Mapping):
        raise TypeError("action must be a mapping")
    normalized: dict[str, Any] = {}
    for key in ALL_ACTION_KEYS:
        value = to_jsonable(action.get(key, [0.0, 0.0] if key == "camera" else 0))
        if key == "camera":
            if not isinstance(value, list) or len(value) != 2:
                raise ValueError("camera action must contain pitch and yaw")
            value = [float(value[0]), float(value[1])]
        normalized[key] = value
    return normalized


def validate_complete_action(action: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(action, Mapping):
        raise TypeError("action must be a mapping")
    if any(not isinstance(key, str) for key in action):
        raise ValueError("action keys must be strings")
    keys = set(action)
    expected = set(POLICY_ACTION_KEYS)
    missing = sorted(expected - keys)
    unknown = sorted(keys - expected)
    if missing or unknown:
        raise ValueError(f"action schema mismatch: missing={missing}, unknown={unknown}")
    normalized = normalize_action(action)
    policy_action = {key: normalized[key] for key in POLICY_ACTION_KEYS}
    for key, value in policy_action.items():
        if key == "camera":
            if any(not math.isfinite(component) for component in value):
                raise ValueError("camera action must contain finite values")
        elif type(value) not in (int, float) or not math.isfinite(float(value)) or value not in (0, 1):
            raise ValueError(f"discrete action {key} must be scalar 0 or 1")
    return policy_action


def _is_active(value: Any) -> bool:
    if isinstance(value, list):
        return any(_is_active(item) for item in value)
    return bool(value)


def active_action_keys(action: Mapping[str, Any]) -> list[str]:
    normalized = normalize_action(action)
    return [key for key in ALL_ACTION_KEYS if _is_active(normalized[key])]


def apply_official_attack_stabilizer(
    action: Mapping[str, Any],
) -> tuple[dict[str, Any], list[str]]:
    """Match the released GUI's locomotion clamp while attack is active."""
    normalized = normalize_action(action)
    stabilized = dict(normalized)
    changed: list[str] = []
    if _is_active(normalized["attack"]):
        for key in OFFICIAL_ATTACK_STABILIZED_KEYS:
            if _is_active(normalized[key]):
                changed.append(key)
            stabilized[key] = 0
    return stabilized, changed


def apply_pilot_safety_mask(action: Mapping[str, Any]) -> tuple[dict[str, Any], list[str]]:
    normalized = normalize_action(action)
    attempted = [key for key in FORBIDDEN_ACTION_KEYS if _is_active(normalized[key])]
    applied = dict(normalized)
    for key in FORBIDDEN_ACTION_KEYS:
        applied[key] = 0
    return applied, attempted


def native_motor_controls(applied_action: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(applied_action, Mapping):
        raise TypeError("applied action must be a mapping")
    keys = set(applied_action)
    expected = set(ALL_ACTION_KEYS)
    missing = sorted(expected - keys)
    unknown = sorted(keys - expected)
    if missing or unknown:
        raise ValueError(f"applied action schema mismatch: missing={missing}, unknown={unknown}")
    normalized = normalize_action(applied_action)
    surviving = [key for key in FORBIDDEN_ACTION_KEYS if _is_active(normalized[key])]
    if surviving:
        raise ValueError(f"forbidden actions survived safety mask: {surviving}")
    return {key: normalized[key] for key in ALLOWED_ACTION_KEYS}


def simulator_action_evidence(
    noop_action: Mapping[str, Any],
    sent_action: Mapping[str, Any],
) -> dict[str, Any]:
    """Canonicalize the exact MineRL action and prove only motor controls changed."""
    if not isinstance(noop_action, Mapping) or not isinstance(sent_action, Mapping):
        raise TypeError("simulator actions must be mappings")
    if any(not isinstance(key, str) for key in (*noop_action.keys(), *sent_action.keys())):
        raise ValueError("simulator action keys must be strings")

    noop = to_jsonable(noop_action)
    sent = to_jsonable(sent_action)
    noop_keys = set(noop)
    sent_keys = set(sent)
    missing = sorted(noop_keys - sent_keys)
    unknown = sorted(sent_keys - noop_keys)
    if missing or unknown:
        raise ValueError(f"simulator action envelope mismatch: missing={missing}, unknown={unknown}")

    changed_keys = sorted(key for key in sent if sent[key] != noop[key])
    unexpected = sorted(set(changed_keys) - set(ALLOWED_ACTION_KEYS))
    if unexpected:
        raise ValueError(f"non-motor simulator controls changed from no-op: {unexpected}")

    active_keys = sorted(key for key, value in sent.items() if _is_active(value))
    unexpected_active = sorted(set(active_keys) - set(ALLOWED_ACTION_KEYS))
    if unexpected_active:
        raise ValueError(f"non-motor simulator controls are active: {unexpected_active}")

    canonical = json.dumps(sent, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {
        "canonical_action": sent,
        "action_keys": sorted(sent),
        "changed_keys": changed_keys,
        "active_keys": active_keys,
        "active_motor_keys": active_keys,
        "sha256": hashlib.sha256(canonical).hexdigest(),
    }


def validate_simulator_motor_membership(
    sent_action: Mapping[str, Any],
    control_spaces: Mapping[str, Any],
) -> dict[str, bool]:
    """Validate each safety-approved value against its real MineRL control space."""
    if not isinstance(sent_action, Mapping) or not isinstance(control_spaces, Mapping):
        raise TypeError("simulator action and control spaces must be mappings")
    missing_actions = sorted(set(ALLOWED_ACTION_KEYS) - set(sent_action))
    missing_spaces = sorted(set(ALLOWED_ACTION_KEYS) - set(control_spaces))
    if missing_actions or missing_spaces:
        raise ValueError(
            "simulator motor schema mismatch: "
            f"missing_actions={missing_actions}, missing_spaces={missing_spaces}"
        )

    membership: dict[str, bool] = {}
    for key in ALLOWED_ACTION_KEYS:
        contains = getattr(control_spaces[key], "contains", None)
        if not callable(contains):
            raise TypeError(f"simulator control space does not expose contains(): {key}")
        try:
            membership[key] = bool(contains(sent_action[key]))
        except Exception as error:
            raise ValueError(f"simulator control-space validation failed for {key}: {error}") from error
    rejected = sorted(key for key, contained in membership.items() if not contained)
    if rejected:
        raise ValueError(f"simulator motor controls are outside their spaces: {rejected}")
    return membership


def latency_summary(samples_ms: Sequence[float], deadline_ms: float = TARGET_LATENCY_MS) -> dict[str, Any]:
    if not samples_ms:
        raise ValueError("latency samples must not be empty")
    if not isinstance(deadline_ms, (int, float)) or isinstance(deadline_ms, bool):
        raise TypeError("deadline_ms must be numeric")
    deadline_ms = float(deadline_ms)
    if not math.isfinite(deadline_ms) or deadline_ms <= 0:
        raise ValueError("deadline_ms must be finite and greater than zero")

    samples: list[float] = []
    for sample in samples_ms:
        if not isinstance(sample, (int, float)) or isinstance(sample, bool):
            raise TypeError("latency samples must be numeric")
        normalized = float(sample)
        if not math.isfinite(normalized) or normalized <= 0:
            raise ValueError("latency samples must be finite and greater than zero")
        samples.append(normalized)
    ordered = sorted(samples)

    def percentile(fraction: float) -> float:
        rank = max(1, math.ceil(fraction * len(ordered)))
        return ordered[rank - 1]

    mean_ms = sum(samples) / len(samples)
    variance = sum((sample - mean_ms) ** 2 for sample in samples) / len(samples)
    deadline_misses = sum(sample > deadline_ms for sample in samples)
    return {
        "count": len(samples),
        "percentile_method": "nearest_rank",
        "total_ms": sum(samples),
        "minimum_ms": ordered[0],
        "maximum_ms": ordered[-1],
        "mean_ms": mean_ms,
        "standard_deviation_ms": math.sqrt(variance),
        "p50_ms": percentile(0.50),
        "p90_ms": percentile(0.90),
        "p95_ms": percentile(0.95),
        "p99_ms": percentile(0.99),
        "effective_hz": 1000.0 / mean_ms,
        "deadline_ms": deadline_ms,
        "deadline_misses": deadline_misses,
        "deadline_miss_rate": deadline_misses / len(samples),
    }


def latency_gate(summary: Mapping[str, Any]) -> dict[str, Any]:
    p95_within_deadline = float(summary["p95_ms"]) <= float(summary["deadline_ms"])
    miss_rate_within_limit = float(summary["deadline_miss_rate"]) < MAX_DEADLINE_MISS_RATE
    return {
        "p95_within_deadline": p95_within_deadline,
        "miss_rate_below_five_percent": miss_rate_within_limit,
        "passed": p95_within_deadline and miss_rate_within_limit,
    }


def uploaded_source_sha256() -> dict[str, str]:
    hashes: dict[str, str] = {}
    for name in ("contract.py", "modal_app.py"):
        path = Path(__file__).with_name(name)
        if not path.is_file():
            raise RuntimeError(f"missing uploaded source file: {path}")
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def pilot_manifest() -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "app_name": APP_NAME,
        "volume_name": VOLUME_NAME,
        "simulator_volume_name": SIMULATOR_VOLUME_NAME,
        "gpu": GPU_TYPE,
        "source": {
            "optimus3_repository": OPTIMUS3_REPOSITORY,
            "optimus3_revision": OPTIMUS3_REVISION,
            "llama_factory_repository": LLAMA_FACTORY_REPOSITORY,
            "llama_factory_revision": LLAMA_FACTORY_REVISION,
        },
        "uploaded_source_sha256": uploaded_source_sha256(),
        "models": [asdict(spec) for spec in MODEL_SPECS],
        "expected_model_bytes": sum(spec.expected_bytes for spec in MODEL_SPECS),
        "runtime": dict(RUNTIME_PINS),
        "simulator_runtime": dict(SIMULATOR_RUNTIME_PINS),
        "simulator_engine": {
            "repository": SIMULATOR_ENGINE_REPOSITORY,
            "revision": SIMULATOR_ENGINE_REVISION,
            "filename": SIMULATOR_ENGINE_FILENAME,
            "expected_bytes": SIMULATOR_ENGINE_EXPECTED_BYTES,
            "sha256": SIMULATOR_ENGINE_SHA256,
            "required_jar": SIMULATOR_ENGINE_JAR,
        },
        "tasks": {
            "synthetic_benchmark": DEFAULT_TASK,
            "native_episode_gate": NATIVE_POLICY_PROMPT,
        },
        "frame_shape": list(FRAME_SHAPE),
        "benchmark_defaults": {
            "reference_steps": DEFAULT_REFERENCE_STEPS,
            "warmup_steps": DEFAULT_WARMUP_STEPS,
            "measured_steps": DEFAULT_MEASURED_STEPS,
            "maximum_total_steps": MAX_TOTAL_BENCHMARK_STEPS,
            "wall_budget_seconds": BENCHMARK_WALL_BUDGET_SECONDS,
            "target_latency_ms": TARGET_LATENCY_MS,
            "maximum_deadline_miss_rate": MAX_DEADLINE_MISS_RATE,
            "synchronize_each_step": True,
        },
        "native_episode_gate": {
            "name": NATIVE_GATE_NAME,
            "claim_scope": (
                "MineStudio native simple iron fixture with the upstream task prompt, released-GUI "
                "reset commands, startup-overlay suppression protocol, and released attack stabilization"
            ),
            "task_config": NATIVE_TASK_CONFIG,
            "task_config_git_blob": NATIVE_TASK_CONFIG_GIT_BLOB,
            "task_config_sha256": NATIVE_TASK_CONFIG_SHA256,
            "upstream_task_text": NATIVE_UPSTREAM_TASK_TEXT,
            "policy_prompt": NATIVE_POLICY_PROMPT,
            "policy_prompt_note": (
                "the native gate now uses the exact released task text; Stage 2 synthetic latency "
                "continues to use the historical default prompt"
            ),
            "environment": native_environment_spec(),
            "upstream_commands": list(NATIVE_UPSTREAM_TASK_COMMANDS),
            "upstream_commands_sha256": NATIVE_UPSTREAM_TASK_COMMANDS_SHA256,
            "fixture_method": NATIVE_FIXTURE_METHOD,
            "fixture_spec": native_fixture_spec(),
            "fixture_spec_sha256": NATIVE_FIXTURE_SPEC_SHA256,
            "fixture_compatibility_note": (
                "reproduces the upstream relative 2x2x2 iron state with absolute setblock commands "
                "because the released engine drops the upstream selector-relative fill command; "
                "AgentStart deterministically supplies the stone pickaxe"
            ),
            "expected_runtime_iron_blocks": NATIVE_EXPECTED_IRON_BLOCKS,
            "runtime_fixture_proof": (
                "query the command target with the released engine's half-open VoxelAction bounds "
                "and require all eight cells to contain iron ore before policy inference"
            ),
            "episode_count": NATIVE_EPISODE_COUNT,
            "required_successes": NATIVE_REQUIRED_SUCCESSES,
            "maximum_policy_steps_per_episode": NATIVE_MAX_STEPS,
            "wall_budget_seconds": NATIVE_SUITE_WALL_BUDGET_SECONDS,
            "wall_budget_enforcement": "cooperative checks between blocking model and simulator calls",
            "capture_wall_budget_seconds": NATIVE_CAPTURE_WALL_BUDGET_SECONDS,
            "outer_hard_timeout_seconds": NATIVE_MODAL_METHOD_TIMEOUT_SECONDS,
            "embedding_seed": NATIVE_EMBEDDING_SEED,
            "expected_label": NATIVE_EXPECTED_LABEL,
            "expected_embedding_sha256": NATIVE_EXPECTED_EMBEDDING_SHA256,
            "expected_projection_sha256": NATIVE_EXPECTED_PROJECTION_SHA256,
            "seed_namespace": NATIVE_SEED_NAMESPACE,
            "world_seeds": list(NATIVE_WORLD_SEEDS),
            "policy_seeds": list(NATIVE_POLICY_SEEDS),
            "oracle": "mine_block.iron_ore delta >= 1 and inventory iron_ore delta >= 1",
            "forbidden_attempts_are_diagnostic": True,
            "attack_stabilizer": {
                "source": "released Optimus-3 GUI postprocessor",
                "when": "attack is active",
                "zeroed_controls": list(OFFICIAL_ATTACK_STABILIZED_KEYS),
            },
            "hard_reset_between_episodes": True,
            "fresh_simulator_process_between_episodes": True,
            "fast_reset": False,
            "video_recording": True,
            "video_recording_note": (
                "copy each policy frame after scored timing and encode only after all scored episodes"
            ),
        },
        "native_diagnostic_episode": {
            "scope": "pre-gate paired diagnostics; never part of the held-out gate",
            "seed_namespace": LEGACY_NATIVE_SEED_NAMESPACE,
            "world_seeds": list(LEGACY_NATIVE_WORLD_SEEDS),
            "policy_seeds": list(LEGACY_NATIVE_POLICY_SEEDS),
            "environment_contract": NATIVE_GATE_NAME,
        },
        "native_failure_replay": {
            "scope": "archived v1 diagnostic; rerun requires commit 5dcfd53",
            "gate": LEGACY_NATIVE_GATE_NAME,
            "seed_namespace": LEGACY_NATIVE_SEED_NAMESPACE,
            "source_result": NATIVE_FAILURE_REPLAY_SOURCE_RESULT,
            "source_result_sha256": NATIVE_FAILURE_REPLAY_SOURCE_SHA256,
            "episode_indices": list(NATIVE_FAILURE_REPLAY_INDICES),
            "world_seeds": [
                LEGACY_NATIVE_WORLD_SEEDS[index] for index in NATIVE_FAILURE_REPLAY_INDICES
            ],
            "policy_seeds": [
                LEGACY_NATIVE_POLICY_SEEDS[index] for index in NATIVE_FAILURE_REPLAY_INDICES
            ],
            "expected_trace_sha256": {
                str(index): NATIVE_FAILURE_REPLAY_TRACE_SHA256[index]
                for index in NATIVE_FAILURE_REPLAY_INDICES
            },
            "expected_applied_action_sha256": {
                str(index): NATIVE_FAILURE_REPLAY_ACTION_SHA256[index]
                for index in NATIVE_FAILURE_REPLAY_INDICES
            },
            "capture": "strict-fixture observation before step 1, then each post-step observation",
            "frame_shape": list(FRAME_SHAPE),
            "frames_per_timeout_episode": NATIVE_MAX_STEPS + 1,
            "video_fps": NATIVE_FAILURE_REPLAY_VIDEO_FPS,
            "video_codec": "h264",
            "maximum_total_video_bytes": NATIVE_FAILURE_REPLAY_MAX_TOTAL_BYTES,
            "unmodified_policy_view_capture": True,
            "environment_hud_present": True,
            "overlays": False,
            "policy_or_environment_inputs_changed": False,
            "exactness_oracle": "replay trace and applied-action sequence SHA-256",
        },
        "allowed_actions": list(ALLOWED_ACTION_KEYS),
        "forbidden_actions": list(FORBIDDEN_ACTION_KEYS),
        "policy_output_actions": list(POLICY_ACTION_KEYS),
        "actuator_only_actions": list(ACTUATOR_ONLY_ACTION_KEYS),
        "cost_controls": {
            "persistent_endpoint": False,
            "min_containers": 0,
            "max_containers": 1,
            "buffer_containers": 0,
            "single_use_containers": True,
            "configured_retries": 0,
            "gpu_startup_timeout_seconds": 900,
            "gpu_method_timeout_seconds": NATIVE_MODAL_METHOD_TIMEOUT_SECONDS,
            "simulator_cpu_cores": 4,
        },
        "scope": "checkpoint load, bounded synthetic latency, and locked native MineStudio competence episodes",
    }


def result_envelope(kind: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    if not re.fullmatch(r"[a-z][a-z0-9_]*", kind):
        raise ValueError("result kind must be snake_case")
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": kind,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "manifest": pilot_manifest(),
        "payload": to_jsonable(payload),
    }


def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--mode",
        choices=(
            "preflight",
            "cache",
            "smoke",
            "bench",
            "engine-cache",
            "sim-preflight",
            "episode",
            "episodes",
        ),
    )
    parser.add_argument("--task", default=DEFAULT_TASK)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--episode-index", type=int, default=0)
    parser.add_argument("--reference-steps", type=int, default=DEFAULT_REFERENCE_STEPS)
    parser.add_argument("--warmup-steps", type=int, default=DEFAULT_WARMUP_STEPS)
    parser.add_argument("--measured-steps", type=int, default=DEFAULT_MEASURED_STEPS)
    args = parser.parse_args()
    manifest = pilot_manifest()
    if args.mode is not None:
        invocation: dict[str, Any] = {"mode": args.mode}
        if args.mode in ("smoke", "bench"):
            invocation.update(
                {
                    "task": validate_task(args.task),
                    "seed": validate_seed(args.seed),
                }
            )
        if args.mode == "bench":
            reference_steps, warmup_steps, measured_steps = validate_benchmark_steps(
                args.reference_steps,
                args.warmup_steps,
                args.measured_steps,
            )
            invocation.update(
                {
                    "reference_steps": reference_steps,
                    "warmup_steps": warmup_steps,
                    "measured_steps": measured_steps,
                }
            )
        if args.mode == "episode":
            episode_index = validate_episode_index(args.episode_index)
            world_seed, policy_seed = native_diagnostic_episode_seeds(episode_index)
            invocation.update(
                {
                    "episode_index": episode_index,
                    "seed_namespace": LEGACY_NATIVE_SEED_NAMESPACE,
                    "world_seed": world_seed,
                    "policy_seed": policy_seed,
                }
            )
        if args.mode == "episodes":
            invocation.update(
                {
                    "episode_indices": list(range(NATIVE_EPISODE_COUNT)),
                    "world_seeds": list(NATIVE_WORLD_SEEDS),
                    "policy_seeds": list(NATIVE_POLICY_SEEDS),
                }
            )
        manifest["invocation"] = invocation
    rendered = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")


if __name__ == "__main__":
    _main()
