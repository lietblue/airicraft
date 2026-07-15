#!/usr/bin/env python3
"""Pure, locally testable contract for the Optimus-3 Modal pilot."""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA_VERSION = 1
APP_NAME = "airicraft-optimus3-pilot"
VOLUME_NAME = "airicraft-optimus3-models"
GPU_TYPE = "L40S"
DEFAULT_TASK = "collect one iron ore"
FRAME_SHAPE = (128, 128, 3)

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
    "qwen_vl_utils": "0.0.11",
    "attention_implementation": "sdpa",
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


def _is_active(value: Any) -> bool:
    if isinstance(value, list):
        return any(_is_active(item) for item in value)
    return bool(value)


def apply_pilot_safety_mask(action: Mapping[str, Any]) -> tuple[dict[str, Any], list[str]]:
    normalized = normalize_action(action)
    attempted = [key for key in FORBIDDEN_ACTION_KEYS if _is_active(normalized[key])]
    applied = dict(normalized)
    for key in FORBIDDEN_ACTION_KEYS:
        applied[key] = 0
    return applied, attempted


def pilot_manifest() -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "app_name": APP_NAME,
        "volume_name": VOLUME_NAME,
        "gpu": GPU_TYPE,
        "source": {
            "optimus3_repository": OPTIMUS3_REPOSITORY,
            "optimus3_revision": OPTIMUS3_REVISION,
            "llama_factory_repository": LLAMA_FACTORY_REPOSITORY,
            "llama_factory_revision": LLAMA_FACTORY_REVISION,
        },
        "models": [asdict(spec) for spec in MODEL_SPECS],
        "expected_model_bytes": sum(spec.expected_bytes for spec in MODEL_SPECS),
        "runtime": dict(RUNTIME_PINS),
        "task": DEFAULT_TASK,
        "frame_shape": list(FRAME_SHAPE),
        "allowed_actions": list(ALLOWED_ACTION_KEYS),
        "forbidden_actions": list(FORBIDDEN_ACTION_KEYS),
        "cost_controls": {
            "persistent_endpoint": False,
            "min_containers": 0,
            "max_containers": 1,
            "buffer_containers": 0,
            "single_use_containers": True,
            "configured_retries": 0,
            "gpu_startup_timeout_seconds": 600,
            "gpu_method_timeout_seconds": 300,
        },
        "scope": "checkpoint load, task embedding, and one synthetic-frame action only",
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
    args = parser.parse_args()
    rendered = json.dumps(pilot_manifest(), indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")


if __name__ == "__main__":
    _main()
