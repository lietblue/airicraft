"""Ephemeral Modal GPU app for the first Optimus-3 motor-policy smoke."""

from __future__ import annotations

import hashlib
import json
import math
import os
import random
import shutil
import sys
import time
import types
from importlib.metadata import version as package_version
from pathlib import Path
from typing import Any, Callable, Mapping

import modal

from contract import (
    ACTION_LABELS,
    ALLOWED_ACTION_KEYS,
    ALL_ACTION_KEYS,
    APP_NAME,
    BENCHMARK_WALL_BUDGET_SECONDS,
    DEFAULT_TASK,
    DEFAULT_MEASURED_STEPS,
    DEFAULT_REFERENCE_STEPS,
    DEFAULT_WARMUP_STEPS,
    FRAME_SHAPE,
    FORBIDDEN_ACTION_KEYS,
    GPU_TYPE,
    LLAMA_FACTORY_REPOSITORY,
    LLAMA_FACTORY_REVISION,
    MODEL_SPECS,
    NATIVE_EMBEDDING_SEED,
    NATIVE_EPISODE_COUNT,
    NATIVE_EXPECTED_EMBEDDING_SHA256,
    NATIVE_EXPECTED_IRON_BLOCKS,
    NATIVE_EXPECTED_LABEL,
    NATIVE_EXPECTED_PROJECTION_SHA256,
    NATIVE_FIXTURE_BLOCK_OFFSETS,
    NATIVE_FIXTURE_METHOD,
    NATIVE_FIXTURE_SETTLE_STEPS,
    NATIVE_FIXTURE_SPEC_SHA256,
    NATIVE_FIXTURE_VOXEL_BOUNDS,
    NATIVE_GATE_NAME,
    NATIVE_MAX_STEPS,
    NATIVE_REQUIRED_SUCCESSES,
    NATIVE_SUITE_WALL_BUDGET_SECONDS,
    NATIVE_TASK_CONFIG,
    NATIVE_TASK_CONFIG_SHA256,
    NATIVE_UPSTREAM_TASK_COMMANDS,
    NATIVE_UPSTREAM_TASK_COMMANDS_SHA256,
    NATIVE_UPSTREAM_TASK_TEXT,
    OPTIMUS3_REPOSITORY,
    OPTIMUS3_REVISION,
    RUNTIME_PINS,
    SIMULATOR_ENGINE_EXPECTED_BYTES,
    SIMULATOR_ENGINE_FILENAME,
    SIMULATOR_ENGINE_JAR,
    SIMULATOR_ENGINE_REPOSITORY,
    SIMULATOR_ENGINE_REVISION,
    SIMULATOR_ENGINE_SHA256,
    SIMULATOR_RUNTIME_PINS,
    SIMULATOR_VOLUME_NAME,
    TARGET_LATENCY_MS,
    VOLUME_NAME,
    active_action_keys,
    apply_pilot_safety_mask,
    latency_gate,
    latency_summary,
    model_spec,
    native_fixture_spec,
    native_motor_controls,
    native_episode_seeds,
    native_episode_success,
    inventory_quantity,
    native_suite_outcome,
    result_envelope,
    simulator_action_evidence,
    stat_count,
    validate_benchmark_steps,
    validate_complete_action,
    validate_episode_index,
    validate_frame_shape,
    validate_seed,
    validate_simulator_motor_membership,
    validate_task,
)


MODEL_ROOT = Path("/models")
SIMULATOR_ROOT = Path("/simulator-engine")
SIMULATOR_RUNTIME_ROOT = Path("/tmp/airicraft-minestudio")
SOURCE_ROOT = Path("/opt/optimus3")
READY_MARKER = ".airicraft-ready.json"
SIMULATOR_READY_MARKER = ".airicraft-ready.json"
LOCAL_CONTRACT_PATH = Path(__file__).with_name("contract.py")
LOCAL_APP_PATH = Path(__file__)

app = modal.App(APP_NAME, include_source=False)
model_volume = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)
simulator_volume = modal.Volume.from_name(SIMULATOR_VOLUME_NAME, create_if_missing=True)
_simulator_verification_cache: tuple[tuple[int, int, int, int], bool] | None = None

SIMULATOR_SUBPROCESS_SITE_CUSTOMIZE = """\
import collections
import collections.abc
import sys
import types
from pathlib import Path

import minestudio

for name in ("Mapping", "MutableMapping", "Sequence"):
    if not hasattr(collections, name):
        setattr(collections, name, getattr(collections.abc, name))

simulator_path = Path("/opt/optimus3/MineStudio/minestudio/simulator")
simulator_module = types.ModuleType("minestudio.simulator")
simulator_module.__path__ = [str(simulator_path)]
simulator_module.__package__ = "minestudio.simulator"
sys.modules["minestudio.simulator"] = simulator_module
minestudio.simulator = simulator_module

callbacks_name = "minestudio.simulator.callbacks"
callbacks_path = simulator_path / "callbacks"
callbacks_module = types.ModuleType(callbacks_name)
callbacks_module.__path__ = [str(callbacks_path)]
callbacks_module.__package__ = callbacks_name
sys.modules[callbacks_name] = callbacks_module
simulator_module.callbacks = callbacks_module

from minestudio.simulator.callbacks.callback import MinecraftCallback

callbacks_module.MinecraftCallback = MinecraftCallback
"""


def _read_only_volume(volume: modal.Volume) -> modal.Volume:
    with_mount_options = getattr(volume, "with_mount_options", None)
    if with_mount_options is not None:
        return with_mount_options(read_only=True)
    return volume.read_only()


download_image = (
    modal.Image.debian_slim(python_version="3.11")
    .env({"HF_XET_HIGH_PERFORMANCE": "1", "HF_HOME": str(MODEL_ROOT / "hf-home")})
    .uv_pip_install("huggingface-hub==0.30.2", "hf-xet==1.1.5")
    .add_local_file(LOCAL_CONTRACT_PATH, "/root/contract.py")
    .add_local_file(LOCAL_APP_PATH, "/root/modal_app.py")
)

runtime_base_image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.4.1-cudnn-devel-ubuntu22.04",
        add_python="3.11",
    )
    .apt_install("build-essential", "git", "libgl1", "libglib2.0-0")
    .uv_pip_install(
        "torch==2.6.0",
        "torchvision==0.21.0",
        index_url="https://download.pytorch.org/whl/cu124",
    )
    .uv_pip_install(
        "accelerate==1.6.0",
        "attrs==25.3.0",
        "datasets==3.6.0",
        "dm-tree==0.1.9",
        "einops==0.8.1",
        "ftfy==6.3.1",
        "gym==0.26.2",
        "gym3==0.3.3",
        "huggingface-hub==0.30.2",
        "numpy==1.26.4",
        "opencv-python-headless==4.11.0.86",
        "packaging==24.2",
        "peft==0.15.2",
        "qwen-vl-utils==0.0.11",
        "safetensors==0.5.3",
        "sentencepiece==0.2.0",
        "tokenizers==0.21.1",
        "transformers==4.51.3",
        "trl==0.9.6",
        "tyro==0.8.14",
        "x-transformers==0.27.1",
    )
    .run_commands(
        "python -m pip install --no-deps "
        f"git+{LLAMA_FACTORY_REPOSITORY}@{LLAMA_FACTORY_REVISION}",
        f"git init {SOURCE_ROOT}",
        f"git -C {SOURCE_ROOT} remote add origin {OPTIMUS3_REPOSITORY}",
        f"git -C {SOURCE_ROOT} fetch --depth 1 origin {OPTIMUS3_REVISION}",
        f"git -C {SOURCE_ROOT} checkout --detach FETCH_HEAD",
        f"python -m pip install --no-deps -e {SOURCE_ROOT}",
        f"python -m pip install --no-deps -e {SOURCE_ROOT / 'MineStudio'}",
    )
    .env(
        {
            "HF_HUB_OFFLINE": "1",
            "HF_HOME": str(MODEL_ROOT / "hf-home"),
            "TOKENIZERS_PARALLELISM": "false",
            "TRANSFORMERS_OFFLINE": "1",
        }
    )
)

runtime_image = (
    runtime_base_image
    .add_local_file(LOCAL_CONTRACT_PATH, "/root/contract.py")
    .add_local_file(LOCAL_APP_PATH, "/root/modal_app.py")
)

simulator_runtime_image = (
    runtime_base_image
    .apt_install(
        "openjdk-8-jre",
        "xvfb",
        "xauth",
        "libgl1-mesa-dri",
        "libgl1-mesa-glx",
        "libglu1-mesa",
        "libegl1",
        "libosmesa6",
        "libsm6",
        "libxrender1",
        "libxext6",
        "libxi6",
        "libxtst6",
        "libxrandr2",
        "libxxf86vm1",
        "libasound2",
        "unzip",
    )
    .uv_pip_install(
        "gymnasium==0.29.1",
        "Pyro4==4.82",
        "psutil==7.0.0",
        "diskcache==5.6.3",
        "lxml==5.4.0",
        "xmltodict==0.14.2",
        "coloredlogs==15.0.1",
        "daemoniker==0.2.3",
        "cuda-python==12.4.0",
        "rich==14.0.0",
        "PyYAML==6.0.2",
        "absl-py==2.2.2",
        "Jinja2==3.1.6",
    )
    .env(
        {
            "LIBGL_ALWAYS_SOFTWARE": "1",
            "MALMO_MINECRAFT_OUTPUT_LOGDIR": str(SIMULATOR_RUNTIME_ROOT / "minecraft-output"),
            "MINESTUDIO_DIR": str(SIMULATOR_RUNTIME_ROOT),
            "MINESTUDIO_GPU_RENDER": "0",
        }
    )
    .add_local_file(LOCAL_CONTRACT_PATH, "/root/contract.py")
    .add_local_file(LOCAL_APP_PATH, "/root/modal_app.py")
)


def _model_path(key: str) -> Path:
    return MODEL_ROOT / model_spec(key).relative_path


def _ready(spec: Any) -> bool:
    target = MODEL_ROOT / spec.relative_path
    marker = target / READY_MARKER
    if not marker.is_file():
        return False
    try:
        metadata = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return metadata.get("revision") == spec.revision and all((target / name).is_file() for name in spec.required_files)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _simulator_ready() -> bool:
    global _simulator_verification_cache

    marker = SIMULATOR_ROOT / SIMULATOR_READY_MARKER
    jar = SIMULATOR_ROOT / SIMULATOR_ENGINE_JAR
    if not marker.is_file() or not jar.is_file():
        return False
    try:
        metadata = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    marker_stat = marker.stat()
    jar_stat = jar.stat()
    signature = (marker_stat.st_mtime_ns, marker_stat.st_size, jar_stat.st_mtime_ns, jar_stat.st_size)
    if _simulator_verification_cache is not None and _simulator_verification_cache[0] == signature:
        return _simulator_verification_cache[1]
    metadata_matches = (
        metadata.get("repository") == SIMULATOR_ENGINE_REPOSITORY
        and metadata.get("revision") == SIMULATOR_ENGINE_REVISION
        and metadata.get("archive_sha256") == SIMULATOR_ENGINE_SHA256
        and metadata.get("archive_bytes") == SIMULATOR_ENGINE_EXPECTED_BYTES
        and metadata.get("jar_bytes") == jar_stat.st_size
    )
    verified = metadata_matches and metadata.get("jar_sha256") == _file_sha256(jar)
    _simulator_verification_cache = (signature, verified)
    return verified


@app.function(
    image=download_image,
    volumes={str(MODEL_ROOT): model_volume},
    timeout=2 * 60 * 60,
    retries=0,
    max_containers=1,
    single_use_containers=True,
)
def cache_weights() -> dict[str, Any]:
    from huggingface_hub import snapshot_download

    started = time.perf_counter()
    cached: list[dict[str, Any]] = []
    for spec in MODEL_SPECS:
        target = MODEL_ROOT / spec.relative_path
        if not _ready(spec):
            target.mkdir(parents=True, exist_ok=True)
            snapshot_download(
                repo_id=spec.repo_id,
                revision=spec.revision,
                local_dir=str(target),
                allow_patterns=list(spec.allow_patterns) if spec.allow_patterns else None,
            )
            missing = [name for name in spec.required_files if not (target / name).is_file()]
            if missing:
                raise RuntimeError(f"{spec.key} download is missing required files: {missing}")
            (target / READY_MARKER).write_text(
                json.dumps({"repo_id": spec.repo_id, "revision": spec.revision}, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        cached.append(
            {
                "key": spec.key,
                "path": str(target),
                "revision": spec.revision,
                "bytes": sum(path.stat().st_size for path in target.rglob("*") if path.is_file()),
            }
        )
    model_volume.commit()
    return result_envelope(
        "weights_cached",
        {"elapsed_seconds": time.perf_counter() - started, "models": cached},
    )


@app.function(
    image=download_image,
    volumes={str(SIMULATOR_ROOT): simulator_volume},
    timeout=2 * 60 * 60,
    retries=0,
    max_containers=1,
    single_use_containers=True,
)
def cache_simulator_engine() -> dict[str, Any]:
    import zipfile

    from huggingface_hub import hf_hub_download

    started = time.perf_counter()
    if not _simulator_ready():
        download_dir = Path("/tmp/airicraft-simulator-download")
        if download_dir.exists():
            shutil.rmtree(download_dir)
        download_dir.mkdir(parents=True)
        archive = Path(
            hf_hub_download(
                repo_id=SIMULATOR_ENGINE_REPOSITORY,
                filename=SIMULATOR_ENGINE_FILENAME,
                revision=SIMULATOR_ENGINE_REVISION,
                local_dir=str(download_dir),
            )
        )
        archive_bytes = archive.stat().st_size
        archive_sha256 = _file_sha256(archive)
        if archive_bytes != SIMULATOR_ENGINE_EXPECTED_BYTES:
            raise RuntimeError(
                f"simulator archive size mismatch: expected {SIMULATOR_ENGINE_EXPECTED_BYTES}, got {archive_bytes}"
            )
        if archive_sha256 != SIMULATOR_ENGINE_SHA256:
            raise RuntimeError(
                f"simulator archive digest mismatch: expected {SIMULATOR_ENGINE_SHA256}, got {archive_sha256}"
            )

        engine_dir = SIMULATOR_ROOT / "engine"
        if engine_dir.exists():
            shutil.rmtree(engine_dir)
        with zipfile.ZipFile(archive, "r") as zip_file:
            root = SIMULATOR_ROOT.resolve()
            for member in zip_file.infolist():
                destination = (SIMULATOR_ROOT / member.filename).resolve()
                if destination != root and root not in destination.parents:
                    raise RuntimeError(f"unsafe simulator archive member: {member.filename}")
            zip_file.extractall(SIMULATOR_ROOT)

        jar = SIMULATOR_ROOT / SIMULATOR_ENGINE_JAR
        if not jar.is_file():
            raise RuntimeError(f"simulator archive is missing required jar: {SIMULATOR_ENGINE_JAR}")
        jar_bytes = jar.stat().st_size
        jar_sha256 = _file_sha256(jar)
        (SIMULATOR_ROOT / SIMULATOR_READY_MARKER).write_text(
            json.dumps(
                {
                    "repository": SIMULATOR_ENGINE_REPOSITORY,
                    "revision": SIMULATOR_ENGINE_REVISION,
                    "archive_bytes": archive_bytes,
                    "archive_sha256": archive_sha256,
                    "required_jar": SIMULATOR_ENGINE_JAR,
                    "jar_bytes": jar_bytes,
                    "jar_sha256": jar_sha256,
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        simulator_volume.commit()

    engine_files = [path for path in (SIMULATOR_ROOT / "engine").rglob("*") if path.is_file()]
    return result_envelope(
        "simulator_engine_cached",
        {
            "elapsed_seconds": time.perf_counter() - started,
            "repository": SIMULATOR_ENGINE_REPOSITORY,
            "revision": SIMULATOR_ENGINE_REVISION,
            "archive_bytes": SIMULATOR_ENGINE_EXPECTED_BYTES,
            "archive_sha256": SIMULATOR_ENGINE_SHA256,
            "required_jar": SIMULATOR_ENGINE_JAR,
            "jar_bytes": (SIMULATOR_ROOT / SIMULATOR_ENGINE_JAR).stat().st_size,
            "jar_sha256": _file_sha256(SIMULATOR_ROOT / SIMULATOR_ENGINE_JAR),
            "extracted_file_count": len(engine_files),
            "extracted_bytes": sum(path.stat().st_size for path in engine_files),
            "ready": _simulator_ready(),
        },
    )


def _install_minestudio_namespace_shim() -> None:
    """Load only simulator modules needed by the policy and native pilot."""
    import collections
    import collections.abc

    for name in ("Mapping", "MutableMapping", "Sequence"):
        if not hasattr(collections, name):
            setattr(collections, name, getattr(collections.abc, name))

    simulator_path = SOURCE_ROOT / "MineStudio" / "minestudio" / "simulator"
    if "minestudio.simulator" not in sys.modules:
        module = types.ModuleType("minestudio.simulator")
        module.__path__ = [str(simulator_path)]
        module.__package__ = "minestudio.simulator"
        sys.modules["minestudio.simulator"] = module

    callbacks_name = "minestudio.simulator.callbacks"
    if callbacks_name not in sys.modules:
        callbacks_path = simulator_path / "callbacks"
        callbacks_module = types.ModuleType(callbacks_name)
        callbacks_module.__path__ = [str(callbacks_path)]
        callbacks_module.__package__ = callbacks_name
        sys.modules[callbacks_name] = callbacks_module
        from minestudio.simulator.callbacks.callback import MinecraftCallback

        callbacks_module.MinecraftCallback = MinecraftCallback


def _prepare_simulator_runtime() -> dict[str, Any]:
    if not _simulator_ready():
        raise RuntimeError("missing pinned simulator engine; run engine-cache mode first")
    if SIMULATOR_RUNTIME_ROOT.exists():
        shutil.rmtree(SIMULATOR_RUNTIME_ROOT)
    SIMULATOR_RUNTIME_ROOT.mkdir(parents=True)
    (SIMULATOR_RUNTIME_ROOT / "runtime").mkdir()
    (SIMULATOR_RUNTIME_ROOT / "tmp").mkdir()
    (SIMULATOR_RUNTIME_ROOT / "minecraft-output").mkdir()
    (SIMULATOR_RUNTIME_ROOT / "engine").symlink_to(SIMULATOR_ROOT / "engine", target_is_directory=True)
    subprocess_shim = SIMULATOR_RUNTIME_ROOT / "subprocess-shim"
    subprocess_shim.mkdir()
    sitecustomize = subprocess_shim / "sitecustomize.py"
    sitecustomize.write_text(SIMULATOR_SUBPROCESS_SITE_CUSTOMIZE, encoding="utf-8")
    inherited_pythonpath = os.environ.get("PYTHONPATH")
    os.environ["PYTHONPATH"] = os.pathsep.join(
        path for path in (str(subprocess_shim), inherited_pythonpath) if path
    )
    os.environ["MINESTUDIO_DIR"] = str(SIMULATOR_RUNTIME_ROOT)
    os.environ["MINESTUDIO_GPU_RENDER"] = "0"
    os.environ["LIBGL_ALWAYS_SOFTWARE"] = "1"
    os.environ["MALMO_MINECRAFT_OUTPUT_LOGDIR"] = str(SIMULATOR_RUNTIME_ROOT / "minecraft-output")
    metadata = json.loads((SIMULATOR_ROOT / SIMULATOR_READY_MARKER).read_text(encoding="utf-8"))
    metadata["subprocess_sitecustomize_sha256"] = _file_sha256(sitecustomize)
    metadata["subprocess_callback_imports"] = SIMULATOR_RUNTIME_PINS["subprocess_callback_imports"]
    return metadata


def _install_clip_tokenizer_shim() -> None:
    from transformers import AutoTokenizer
    import minestudio.utils.mineclip_lib.mineclip.tokenization as mineclip_tokenization

    tokenizer = AutoTokenizer.from_pretrained(str(_model_path("clip_tokenizer")), local_files_only=True)
    mineclip_tokenization.get_tokenizer = lambda _name, use_fast=True: tokenizer


def _native_task_source_metadata() -> dict[str, Any]:
    path = SOURCE_ROOT / NATIVE_TASK_CONFIG
    if not path.is_file():
        raise RuntimeError(f"missing pinned native task config: {path}")
    digest = _file_sha256(path)
    if digest != NATIVE_TASK_CONFIG_SHA256:
        raise RuntimeError(
            f"native task config digest mismatch: expected {NATIVE_TASK_CONFIG_SHA256}, got {digest}"
        )
    upstream_commands_digest = hashlib.sha256(
        ("\n".join(NATIVE_UPSTREAM_TASK_COMMANDS) + "\n").encode("utf-8")
    ).hexdigest()
    if upstream_commands_digest != NATIVE_UPSTREAM_TASK_COMMANDS_SHA256:
        raise RuntimeError(
            "native upstream command digest mismatch: "
            f"expected {NATIVE_UPSTREAM_TASK_COMMANDS_SHA256}, got {upstream_commands_digest}"
        )
    fixture_spec_digest = hashlib.sha256(
        json.dumps(native_fixture_spec(), sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if fixture_spec_digest != NATIVE_FIXTURE_SPEC_SHA256:
        raise RuntimeError(
            f"native fixture spec digest mismatch: expected {NATIVE_FIXTURE_SPEC_SHA256}, got {fixture_spec_digest}"
        )
    return {
        "path": str(path),
        "sha256": digest,
        "upstream_commands_sha256": upstream_commands_digest,
        "fixture_method": NATIVE_FIXTURE_METHOD,
        "fixture_spec_sha256": fixture_spec_digest,
    }


def _new_native_simulator(world_seed: int) -> Any:
    _install_minestudio_namespace_shim()
    from minestudio.simulator.entry import MinecraftSim

    simulator = MinecraftSim(
        action_type="env",
        obs_size=FRAME_SHAPE[:2],
        render_size=(640, 360),
        seed=world_seed,
        inventory={0: {"type": "stone_pickaxe", "quantity": 1}},
        preferred_spawn_biome=None,
        num_empty_frames=20,
        callbacks=[],
    )
    task = simulator.env.task
    simulator.airicraft_original_create_agent_start = task.create_agent_start
    simulator.airicraft_original_create_observables = task.create_observables
    simulator.airicraft_original_create_server_decorators = task.create_server_decorators
    return simulator


def _restore_native_mission(simulator: Any) -> None:
    task = simulator.env.task
    task.create_agent_start = simulator.airicraft_original_create_agent_start
    task.create_observables = simulator.airicraft_original_create_observables
    task.create_server_decorators = simulator.airicraft_original_create_server_decorators


def _configure_native_mission_fixture(
    simulator: Any,
    discovery_location: Mapping[str, float],
) -> dict[str, Any]:
    _install_minestudio_namespace_shim()
    from minestudio.simulator.minerl.herobraine.hero.handlers.agent.start import AgentStartPlacement

    required_location_keys = {"xpos", "ypos", "zpos", "pitch", "yaw"}
    missing = sorted(required_location_keys - set(discovery_location))
    if missing:
        raise RuntimeError(f"native discovery reset is missing location fields: {missing}")

    base_x = math.floor(discovery_location["xpos"])
    base_y = math.floor(discovery_location["ypos"])
    base_z = math.floor(discovery_location["zpos"])
    expected_blocks = [
        {
            "x": base_x + offset_x,
            "y": base_y + offset_y,
            "z": base_z + offset_z,
            "type": "iron_ore",
        }
        for offset_x, offset_y, offset_z in NATIVE_FIXTURE_BLOCK_OFFSETS
    ]
    placement = {
        "x": discovery_location["xpos"],
        "y": discovery_location["ypos"],
        "z": discovery_location["zpos"],
        "yaw": discovery_location["yaw"],
        "pitch": discovery_location["pitch"],
    }
    original_agent_start = simulator.airicraft_original_create_agent_start

    def create_agent_start(_task: Any) -> list[Any]:
        return [*original_agent_start(), AgentStartPlacement(**placement)]

    task = simulator.env.task
    task.create_agent_start = types.MethodType(create_agent_start, task)
    return {
        "method": NATIVE_FIXTURE_METHOD,
        "spec_sha256": NATIVE_FIXTURE_SPEC_SHA256,
        "upstream_commands": list(NATIVE_UPSTREAM_TASK_COMMANDS),
        "discovery_location": dict(discovery_location),
        "placement": placement,
        "expected_blocks": expected_blocks,
        "settle_noop_steps": NATIVE_FIXTURE_SETTLE_STEPS,
        "voxel_bounds_half_open": list(NATIVE_FIXTURE_VOXEL_BOUNDS),
    }


def _frame_sha256(frame: Any) -> str:
    validate_frame_shape(frame)
    return hashlib.sha256(frame.tobytes()).hexdigest()


def _mainhand_type(info: Mapping[str, Any]) -> str:
    equipped = info.get("equipped_items", {})
    if isinstance(equipped, Mapping):
        mainhand = equipped.get("mainhand", {})
        if isinstance(mainhand, Mapping):
            return str(mainhand.get("type", "")).removeprefix("minecraft:")
    return ""


def _count_voxel_type(value: Any, item: str) -> int:
    if isinstance(value, Mapping):
        return sum(_count_voxel_type(item_value, item) for item_value in value.values())
    if isinstance(value, (list, tuple)):
        return sum(_count_voxel_type(item_value, item) for item_value in value)
    if hasattr(value, "tolist"):
        return _count_voxel_type(value.tolist(), item)
    if isinstance(value, str):
        return int(value.removeprefix("minecraft:") == item)
    return 0


def _numeric(value: Any) -> float:
    if hasattr(value, "item"):
        value = value.item()
    return float(value)


def _location(info: Mapping[str, Any]) -> dict[str, float] | None:
    location = info.get("location_stats")
    if not isinstance(location, Mapping):
        return None
    result: dict[str, float] = {}
    for key in ("xpos", "ypos", "zpos", "pitch", "yaw"):
        if key in location:
            result[key] = _numeric(location[key])
    return result or None


def _inventory_snapshot(info: Mapping[str, Any]) -> list[dict[str, Any]]:
    inventory = info.get("inventory", {})
    if not isinstance(inventory, Mapping):
        return []
    snapshot: list[dict[str, Any]] = []
    for slot, item in inventory.items():
        if not isinstance(item, Mapping):
            continue
        quantity = _numeric(item.get("quantity", 0))
        item_type = str(item.get("type", "")).removeprefix("minecraft:")
        if quantity > 0 and item_type not in ("", "air"):
            snapshot.append({"slot": str(slot), "type": item_type, "quantity": quantity})
    return sorted(snapshot, key=lambda item: item["slot"])


def _simulator_noop(simulator: Any) -> dict[str, Any]:
    action = simulator.env.action_space.no_op()
    if not isinstance(action, Mapping):
        raise RuntimeError("MineStudio no-op action is not a mapping")
    return dict(action)


def _query_native_voxels(
    simulator: Any,
    bounds: tuple[int, int, int, int, int, int],
    expected_iron_blocks: int,
) -> tuple[Any, dict[str, Any], dict[str, Any]]:
    query = _simulator_noop(simulator)
    query["voxels"] = list(bounds)
    observation, _reward, terminated, truncated, info = simulator.step(query)
    if terminated or truncated:
        raise RuntimeError("native fixture terminated during voxel validation")
    voxel_value = info.get("voxels")
    iron_blocks = _count_voxel_type(voxel_value, "iron_ore")
    evidence = {
        "method": "released_engine_voxel_action",
        "bounds_semantics": "half_open",
        "bounds": list(bounds),
        "expected_volume": expected_iron_blocks,
        "iron_blocks": iron_blocks,
        "passed": iron_blocks == expected_iron_blocks,
        "python_type": f"{type(voxel_value).__module__}.{type(voxel_value).__qualname__}",
        "value": voxel_value,
    }
    return observation, info, evidence


def _materialize_native_fixture(
    simulator: Any,
    expected_blocks: list[dict[str, Any]],
) -> tuple[Any, dict[str, Any], dict[str, Any]]:
    command_outcomes: list[dict[str, Any]] = []
    for block in expected_blocks:
        command = f'/setblock {block["x"]} {block["y"]} {block["z"]} minecraft:iron_ore'
        command_observation, reward, done, command_info = simulator.env.execute_cmd(command)
        command_outcomes.append(
            {
                "command": command,
                "reward": float(reward),
                "done": bool(done),
                "observation_keys": sorted(command_observation or {}),
                "info_keys": sorted(command_info or {}),
            }
        )
        if done:
            raise RuntimeError(f"native fixture command terminated the environment: {command}")

    observation = None
    info: dict[str, Any] = {}
    terminated = False
    truncated = False
    for _ in range(NATIVE_FIXTURE_SETTLE_STEPS):
        observation, _reward, terminated, truncated, info = simulator.step(_simulator_noop(simulator))
        if terminated or truncated:
            raise RuntimeError("native fixture terminated while settling command effects")

    observation, info, runtime_query = _query_native_voxels(
        simulator,
        NATIVE_FIXTURE_VOXEL_BOUNDS,
        NATIVE_EXPECTED_IRON_BLOCKS,
    )
    evidence = {
        "upstream_commands_executed": False,
        "compatibility_reason": (
            "the released engine applies absolute setblock commands but drops the upstream "
            "selector-relative fill command"
        ),
        "materialization": "one absolute setblock command per expected block",
        "command_outcomes": command_outcomes,
        "settle_noop_steps": NATIVE_FIXTURE_SETTLE_STEPS,
        "runtime_query": runtime_query,
    }
    return observation, info, evidence


def _coerce_action_value(value: Any, template: Any, np_module: Any) -> Any:
    if hasattr(template, "dtype") and hasattr(template, "shape"):
        coerced = np_module.asarray(value, dtype=template.dtype)
        return coerced.reshape(template.shape)
    if hasattr(template, "dtype"):
        return np_module.asarray(value, dtype=template.dtype)
    if type(template) is int:
        return int(value)
    if type(template) is float:
        return float(value)
    return value


def _native_simulator_action(
    simulator: Any,
    applied_action: Mapping[str, Any],
    np_module: Any,
) -> tuple[dict[str, Any], dict[str, Any]]:
    noop = _simulator_noop(simulator)
    action = dict(noop)
    controls = native_motor_controls(applied_action)
    missing = sorted(set(ALLOWED_ACTION_KEYS) - set(action))
    if missing:
        raise RuntimeError(f"MineStudio action space is missing allowed controls: {missing}")
    for key in ALLOWED_ACTION_KEYS:
        action[key] = _coerce_action_value(controls[key], action[key], np_module)

    evidence = simulator_action_evidence(noop, action)
    action_space = simulator.env.action_space
    spaces = getattr(action_space, "spaces", None)
    if not isinstance(spaces, Mapping):
        raise RuntimeError("MineStudio action space does not expose per-control spaces")
    membership = validate_simulator_motor_membership(action, spaces)

    whole_contains: bool | None = None
    whole_contains_error: str | None = None
    whole_contains_call = getattr(action_space, "contains", None)
    if callable(whole_contains_call):
        try:
            whole_contains = bool(whole_contains_call(action))
        except Exception as error:
            whole_contains_error = f"{type(error).__name__}: {error}"
    evidence.update(
        {
            "motor_control_membership": membership,
            "motor_controls_in_space": True,
            "whole_action_space_contains_diagnostic": whole_contains,
            "whole_action_space_contains_error": whole_contains_error,
            "action_space_type": f"{type(action_space).__module__}.{type(action_space).__qualname__}",
        }
    )
    return action, evidence


def _native_reset(
    simulator: Any,
    world_seed: int,
    *,
    strict_fixture: bool,
) -> tuple[Any, dict[str, Any], dict[str, Any]]:
    _restore_native_mission(simulator)
    simulator.env.seed(world_seed)
    discovery_observation, discovery_info = simulator.reset()
    validate_frame_shape(discovery_observation.get("image"))
    discovery_location = _location(discovery_info)
    if discovery_location is None:
        raise RuntimeError("native discovery reset did not return a location")
    mission_fixture = _configure_native_mission_fixture(simulator, discovery_location)

    simulator.env.seed(world_seed)
    observation, info = simulator.reset()
    rendered_mission = simulator.env.task.to_xml()
    observation, info, runtime_materialization = _materialize_native_fixture(
        simulator,
        mission_fixture["expected_blocks"],
    )
    mission_fixture.update(
        {
            "hard_resets": 2,
            "rendered_mission_sha256": hashlib.sha256(rendered_mission.encode("utf-8")).hexdigest(),
            "rendered_mission_contains_privileged_observer": any(
                marker in rendered_mission
                for marker in ("<ObservationFromGrid", "<ObservationFromRay")
            ),
            **runtime_materialization,
        }
    )
    frame = observation.get("image")
    validate_frame_shape(frame)
    mainhand = _mainhand_type(info)
    initial_iron = inventory_quantity(info.get("inventory"), "iron_ore")
    baseline = {
        "inventory_iron_ore": initial_iron,
        "mine_iron_ore": stat_count(info.get("mine_block"), "iron_ore"),
        "pickup_iron_ore": stat_count(info.get("pickup"), "iron_ore"),
    }
    setup = {
        "frame_sha256": _frame_sha256(frame),
        "runtime_iron_blocks": mission_fixture["runtime_query"]["iron_blocks"],
        "mainhand": mainhand,
        "inventory": _inventory_snapshot(info),
        "location": _location(info),
        "baseline": baseline,
        "mission_fixture": mission_fixture,
    }
    fixture_errors: list[str] = []
    if mission_fixture["runtime_query"]["passed"] is not True:
        fixture_errors.append(
            f"expected {NATIVE_EXPECTED_IRON_BLOCKS} runtime iron blocks, "
            f"got {mission_fixture['runtime_query']['iron_blocks']}"
        )
    if mission_fixture["rendered_mission_contains_privileged_observer"]:
        fixture_errors.append("scored policy mission unexpectedly contains a privileged observer")
    if mainhand != "stone_pickaxe":
        fixture_errors.append(f"expected mainhand stone_pickaxe, got {mainhand!r}")
    if initial_iron != 0:
        fixture_errors.append(f"expected zero initial iron ore, got {initial_iron}")
    final_location = setup["location"]
    if final_location is None or any(
        abs(final_location[key] - discovery_location[key]) > 0.01
        for key in ("xpos", "ypos", "zpos")
    ):
        fixture_errors.append("mission placement does not match the discovery location")
    setup["fixture_errors"] = fixture_errors
    if fixture_errors and strict_fixture:
        diagnostics = json.dumps(setup, sort_keys=True, separators=(",", ":"))
        raise RuntimeError(f"native fixture mismatch: {'; '.join(fixture_errors)}; diagnostics={diagnostics}")
    return observation, info, setup


@app.function(
    image=simulator_runtime_image,
    volumes={str(SIMULATOR_ROOT): _read_only_volume(simulator_volume)},
    cpu=4.0,
    memory=16_384,
    startup_timeout=15 * 60,
    timeout=15 * 60,
    retries=0,
    min_containers=0,
    max_containers=1,
    buffer_containers=0,
    scaledown_window=60,
    single_use_containers=True,
)
def simulator_preflight() -> dict[str, Any]:
    import subprocess
    import numpy as np

    engine_metadata = _prepare_simulator_runtime()
    task_metadata = _native_task_source_metadata()
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False, timeout=30)
    simulator = None
    started = time.perf_counter()
    try:
        world_seed, _policy_seed = native_episode_seeds(0)
        simulator = _new_native_simulator(world_seed)
        observation, info, setup = _native_reset(
            simulator,
            world_seed,
            strict_fixture=False,
        )
        masked_noop = {key: [0.0, 0.0] if key == "camera" else 0 for key in ALL_ACTION_KEYS}
        simulator_action, adapter_evidence = _native_simulator_action(simulator, masked_noop, np)
        adapter_started = time.perf_counter()
        observation, _reward, terminated, truncated, info = simulator.step(simulator_action)
        adapter_seconds = time.perf_counter() - adapter_started
        checks = {
            "engine_ready": _simulator_ready(),
            "task_source_pinned": task_metadata["sha256"] == NATIVE_TASK_CONFIG_SHA256,
            "upstream_commands_pinned": task_metadata["upstream_commands_sha256"]
            == NATIVE_UPSTREAM_TASK_COMMANDS_SHA256,
            "fixture_spec_pinned": task_metadata["fixture_spec_sha256"] == NATIVE_FIXTURE_SPEC_SHA256,
            "java_8": 'version "1.8.' in (java.stderr + java.stdout),
            "java_exit_zero": java.returncode == 0,
            "frame_shape_valid": list(observation["image"].shape) == list(FRAME_SHAPE),
            "fixture_runtime_iron_blocks_valid": setup["runtime_iron_blocks"]
            == NATIVE_EXPECTED_IRON_BLOCKS,
            "fixture_voxel_oracle_valid": setup["mission_fixture"]["runtime_query"]["passed"] is True,
            "fixture_errors_empty": not setup["fixture_errors"],
            "fixture_mainhand_valid": setup["mainhand"] == "stone_pickaxe",
            "fixture_initial_inventory_valid": setup["baseline"]["inventory_iron_ore"] == 0,
            "action_adapter_changed_no_controls": adapter_evidence["changed_keys"] == [],
            "action_adapter_motor_controls_in_space": adapter_evidence["motor_controls_in_space"],
            "adapter_step_did_not_terminate": not terminated and not truncated,
        }
        return result_envelope(
            "simulator_preflight",
            {
                "gate": NATIVE_GATE_NAME,
                "engine": engine_metadata,
                "task_source": task_metadata,
                "java_version": (java.stderr or java.stdout).strip(),
                "renderer": SIMULATOR_RUNTIME_PINS["renderer"],
                "world_seed": world_seed,
                "setup": setup,
                "action_adapter": adapter_evidence,
                "post_adapter_step": {
                    "frame_sha256": _frame_sha256(observation["image"]),
                    "inventory": _inventory_snapshot(info),
                    "location": _location(info),
                },
                "underlying_action_keys": sorted(_simulator_noop(simulator)),
                "timing": {
                    "total_seconds": time.perf_counter() - started,
                    "adapter_step_seconds": adapter_seconds,
                },
                "acceptance": {"checks": checks, "passed": all(checks.values())},
            },
        )
    finally:
        if simulator is not None:
            simulator.close()


class _NativeEpisodeMixin:
    def _native_episode(
        self,
        simulator: Any,
        projected: Any,
        episode_index: int,
        wall_deadline: float,
    ) -> dict[str, Any]:
        world_seed, policy_seed = native_episode_seeds(episode_index)
        observation, info, setup = _native_reset(
            simulator,
            world_seed,
            strict_fixture=True,
        )
        baseline = setup["baseline"]
        self._reset_action_policy(policy_seed)

        records: list[dict[str, Any]] = []
        success = False
        valid = True
        failure_reason: str | None = None
        completion_step: int | None = None
        initial_location = setup["location"]
        previous_location = initial_location
        distance_travelled = 0.0

        for step in range(1, NATIVE_MAX_STEPS + 1):
            if time.perf_counter() >= wall_deadline:
                valid = False
                failure_reason = "wall_budget_exhausted"
                break

            frame = observation["image"]
            input_frame_sha256 = _frame_sha256(frame)
            loop_started_ns = time.perf_counter_ns()
            try:
                record = self._timed_action(lambda: self._cached_equivalent_action(projected, frame))
            except (TypeError, ValueError) as error:
                valid = False
                failure_reason = "policy_schema_error"
                records.append(
                    {
                        "step": step,
                        "input_frame_sha256": input_frame_sha256,
                        "error": {"type": type(error).__name__, "message": str(error)},
                    }
                )
                break
            except Exception as error:
                valid = False
                failure_reason = "policy_inference_error"
                records.append(
                    {
                        "step": step,
                        "input_frame_sha256": input_frame_sha256,
                        "error": {"type": type(error).__name__, "message": str(error)},
                    }
                )
                break

            record.update({"phase": "native_episode", "index": step - 1, "step": step})
            record["input_frame_sha256"] = input_frame_sha256
            if record["safety_violations"]:
                valid = False
                failure_reason = "safety_violation"
                records.append(record)
                break

            try:
                simulator_action, sent_action = _native_simulator_action(
                    simulator, record["applied_action"], self.np
                )
                record["sent_action"] = sent_action
            except Exception as error:
                valid = False
                failure_reason = "action_adapter_error"
                record["error"] = {"type": type(error).__name__, "message": str(error)}
                records.append(record)
                break

            env_started_ns = time.perf_counter_ns()
            try:
                observation, reward, terminated, truncated, info = simulator.step(simulator_action)
            except Exception as error:
                valid = False
                failure_reason = "simulator_step_error"
                record["error"] = {"type": type(error).__name__, "message": str(error)}
                records.append(record)
                break
            env_finished_ns = time.perf_counter_ns()

            current_location = _location(info)
            if previous_location is not None and current_location is not None:
                dx = current_location.get("xpos", 0.0) - previous_location.get("xpos", 0.0)
                dy = current_location.get("ypos", 0.0) - previous_location.get("ypos", 0.0)
                dz = current_location.get("zpos", 0.0) - previous_location.get("zpos", 0.0)
                distance_travelled += (dx * dx + dy * dy + dz * dz) ** 0.5
            previous_location = current_location

            inventory_iron = inventory_quantity(info.get("inventory"), "iron_ore")
            mine_iron = stat_count(info.get("mine_block"), "iron_ore")
            pickup_iron = stat_count(info.get("pickup"), "iron_ore")
            success = native_episode_success(info, baseline)
            record.update(
                {
                    "env_step_ms": (env_finished_ns - env_started_ns) / 1_000_000.0,
                    "closed_loop_ms": (time.perf_counter_ns() - loop_started_ns) / 1_000_000.0,
                    "reward": _numeric(reward),
                    "terminated": bool(terminated),
                    "truncated": bool(truncated),
                    "inventory_iron_ore": inventory_iron,
                    "mine_iron_ore": mine_iron,
                    "pickup_iron_ore": pickup_iron,
                    "oracle_success": success,
                    "location": current_location,
                }
            )
            records.append(record)
            if success:
                completion_step = step
                break
            if terminated or truncated:
                valid = False
                failure_reason = "unexpected_environment_termination"
                break

        if valid and not success and failure_reason is None:
            failure_reason = "timeout_200_steps"

        complete_records = [record for record in records if "raw_action" in record]
        action_evidence = self._action_evidence(complete_records)
        trace_digest = hashlib.sha256()
        for record in records:
            canonical_record = {
                key: record[key]
                for key in (
                    "step",
                    "input_frame_sha256",
                    "raw_action",
                    "applied_action",
                    "sent_action",
                    "forbidden_attempts",
                    "terminated",
                    "truncated",
                    "inventory_iron_ore",
                    "mine_iron_ore",
                    "pickup_iron_ore",
                    "oracle_success",
                    "location",
                )
                if key in record
            }
            if "error" in record:
                canonical_record["error_type"] = record["error"]["type"]
            trace_digest.update(
                json.dumps(canonical_record, sort_keys=True, separators=(",", ":")).encode("utf-8")
            )

        def summarize(name: str) -> dict[str, Any] | None:
            samples = [float(record[name]) for record in complete_records if name in record]
            return latency_summary(samples) if samples else None

        final_inventory = _inventory_snapshot(info)
        final_inventory_iron = inventory_quantity(info.get("inventory"), "iron_ore")
        final_mine_iron = stat_count(info.get("mine_block"), "iron_ore")
        final_pickup_iron = stat_count(info.get("pickup"), "iron_ore")
        attack_steps = sum(record["applied_action"]["attack"] == 1 for record in complete_records)
        camera_travel = sum(
            abs(float(record["applied_action"]["camera"][0]))
            + abs(float(record["applied_action"]["camera"][1]))
            for record in complete_records
        )
        return {
            "episode_index": episode_index,
            "world_seed": world_seed,
            "policy_seed": policy_seed,
            "valid": valid,
            "success": success,
            "failure_reason": failure_reason,
            "completion_step": completion_step,
            "policy_steps": len(complete_records),
            "setup": setup,
            "final": {
                "frame_sha256": _frame_sha256(observation["image"]),
                "inventory": final_inventory,
                "location": _location(info),
                "inventory_iron_ore": final_inventory_iron,
                "mine_iron_ore": final_mine_iron,
                "pickup_iron_ore": final_pickup_iron,
                "inventory_iron_ore_delta": final_inventory_iron - baseline["inventory_iron_ore"],
                "mine_iron_ore_delta": final_mine_iron - baseline["mine_iron_ore"],
                "pickup_iron_ore_delta": final_pickup_iron - baseline["pickup_iron_ore"],
            },
            "metrics": {
                "policy_call_ms": summarize("policy_call_ms"),
                "native_action_ms": summarize("native_step_ms"),
                "environment_step_ms": summarize("env_step_ms"),
                "closed_loop_ms": summarize("closed_loop_ms"),
                "distance_travelled": distance_travelled,
                "attack_steps": attack_steps,
                "attack_duty_cycle": attack_steps / len(complete_records) if complete_records else 0.0,
                "camera_travel_degrees": camera_travel,
            },
            "action_evidence": action_evidence,
            "action_trace_sha256": trace_digest.hexdigest(),
            "trace": records,
        }

    def _run_native(self, episode_indices: list[int]) -> dict[str, Any]:
        episode_indices = [validate_episode_index(index) for index in episode_indices]
        if not episode_indices:
            raise ValueError("at least one native episode is required")
        if len(set(episode_indices)) != len(episode_indices):
            raise ValueError("native episode indices must be unique")

        method_started = time.perf_counter()
        wall_budget_seconds = 10 * 60.0 if len(episode_indices) == 1 else NATIVE_SUITE_WALL_BUDGET_SECONDS
        wall_deadline = method_started + wall_budget_seconds
        engine_metadata = _prepare_simulator_runtime()
        task_metadata = _native_task_source_metadata()

        self._set_seed(NATIVE_EMBEDDING_SEED)
        embedding_started = time.perf_counter()
        embedding, task_label = self._task_embedding(DEFAULT_TASK)
        self.torch.cuda.synchronize()
        embedding_seconds = time.perf_counter() - embedding_started
        embedding_cpu = embedding.detach().cpu().numpy()
        embedding_sha256 = hashlib.sha256(embedding_cpu.tobytes()).hexdigest()

        self.torch.cuda.synchronize()
        projection_started = time.perf_counter()
        with self.torch.inference_mode():
            projected = self._project_task_embedding(embedding)
        self.torch.cuda.synchronize()
        projection_seconds = time.perf_counter() - projection_started
        projected_cpu = projected.detach().cpu().numpy()
        projection_sha256 = hashlib.sha256(projected_cpu.tobytes()).hexdigest()

        conditioning_checks = {
            "task_label_is_iron": task_label == NATIVE_EXPECTED_LABEL,
            "embedding_digest_matches_stage_2": embedding_sha256 == NATIVE_EXPECTED_EMBEDDING_SHA256,
            "projection_digest_matches_stage_2": projection_sha256 == NATIVE_EXPECTED_PROJECTION_SHA256,
        }
        episodes: list[dict[str, Any]] = []
        simulator = None
        suite_error: dict[str, str] | None = None
        if all(conditioning_checks.values()):
            try:
                first_world_seed, _first_policy_seed = native_episode_seeds(episode_indices[0])
                simulator = _new_native_simulator(first_world_seed)
                for episode_index in episode_indices:
                    if time.perf_counter() >= wall_deadline:
                        suite_error = {
                            "type": "WallBudgetExceeded",
                            "message": "native episode wall budget exhausted before the next reset",
                        }
                        break
                    try:
                        episode = self._native_episode(
                            simulator,
                            projected,
                            episode_index,
                            wall_deadline,
                        )
                    except Exception as error:
                        episode = {
                            "episode_index": episode_index,
                            "world_seed": native_episode_seeds(episode_index)[0],
                            "policy_seed": native_episode_seeds(episode_index)[1],
                            "valid": False,
                            "success": False,
                            "failure_reason": "fixture_or_reset_error",
                            "error": {"type": type(error).__name__, "message": str(error)},
                            "trace": [],
                        }
                    episodes.append(episode)
                    if not episode["valid"]:
                        break
            except Exception as error:
                suite_error = {"type": type(error).__name__, "message": str(error)}
            finally:
                if simulator is not None:
                    simulator.close()

        completed = len(episodes) == len(episode_indices)
        valid_episodes = sum(bool(episode.get("valid")) for episode in episodes)
        successes = sum(
            bool(episode.get("valid")) and bool(episode.get("success")) for episode in episodes
        )
        safety_violations = sum(
            len(episode.get("action_evidence", {}).get("safety_violations", [])) for episode in episodes
        )
        schema_failures = sum(episode.get("failure_reason") == "policy_schema_error" for episode in episodes)
        first_setup = episodes[0].get("setup", {}) if episodes else {}
        first_mission_fixture = first_setup.get("mission_fixture", {})
        fixture_voxel_oracle_valid = first_mission_fixture.get("runtime_query", {}).get("passed") is True
        infrastructure_checks = {
            "requested_gpu_present": GPU_TYPE.lower() in self.cuda_device_name.lower(),
            "torch_runtime_matches_pin": self.torch.__version__ == RUNTIME_PINS["torch"],
            "cuda_runtime_matches_pin": self.torch.version.cuda == RUNTIME_PINS["cuda"],
            "pinned_checkpoints_ready": all(_ready(spec) for spec in MODEL_SPECS),
            "pinned_simulator_engine_ready": _simulator_ready(),
            "pinned_task_source_ready": task_metadata["sha256"] == NATIVE_TASK_CONFIG_SHA256,
            "pinned_upstream_commands_ready": task_metadata["upstream_commands_sha256"]
            == NATIVE_UPSTREAM_TASK_COMMANDS_SHA256,
            "pinned_fixture_spec_ready": task_metadata["fixture_spec_sha256"]
            == NATIVE_FIXTURE_SPEC_SHA256,
            "fixture_voxel_oracle_valid": fixture_voxel_oracle_valid,
            **conditioning_checks,
            "all_requested_episodes_completed": completed,
            "all_completed_episodes_valid": valid_episodes == len(episode_indices),
            "schema_failures_zero": schema_failures == 0,
            "safety_violations_zero": safety_violations == 0,
            "suite_error_absent": suite_error is None,
        }
        infrastructure_passed = all(infrastructure_checks.values())
        gate_requested = episode_indices == list(range(NATIVE_EPISODE_COUNT))
        outcome = native_suite_outcome(episodes, infrastructure_passed)
        gate_checks = {
            **infrastructure_checks,
            "exact_locked_episode_schedule": outcome["exact_locked_episode_schedule"],
            "ten_valid_episodes": outcome["complete_and_valid"],
            "successes_at_least_eight": outcome["successes"] >= NATIVE_REQUIRED_SUCCESSES,
        }
        kind = "model_native_episode_suite" if gate_requested else "model_native_episode"
        return result_envelope(
            kind,
            {
                "gate": NATIVE_GATE_NAME,
                "gate_requested": gate_requested,
                "gate_evaluated": outcome["evaluated"],
                "task": DEFAULT_TASK,
                "task_label": task_label,
                "episode_indices": episode_indices,
                "protocol": {
                    "claim_scope": (
                        "native MineStudio simple iron fixture with the locked Stage 2 continuity prompt; "
                        "not upstream-prompt parity and not a visible-iron test"
                    ),
                    "upstream_task_text": NATIVE_UPSTREAM_TASK_TEXT,
                    "policy_prompt": DEFAULT_TASK,
                    "policy_prompt_note": (
                        "retained from Stages 1 and 2 for conditioning continuity; the upstream task "
                        "text is recorded but is not the model input"
                    ),
                    "maximum_policy_steps_per_episode": NATIVE_MAX_STEPS,
                    "required_successes": NATIVE_REQUIRED_SUCCESSES,
                    "wall_budget_seconds": wall_budget_seconds,
                    "wall_budget_enforcement": (
                        "cooperative checks between blocking reset, inference, and simulator-step calls"
                    ),
                    "outer_hard_timeout_seconds": 1800,
                    "outer_hard_timeout_note": (
                        "the Modal function timeout is the hard stop if a blocking JVM or model call hangs"
                    ),
                    "hard_reset_between_episodes": True,
                    "reuse_minecraft_process": True,
                    "policy_reset_once_per_episode": True,
                    "policy_warmup_steps": 0,
                    "per_frame_stochastic_prior_preserved": True,
                    "fallback_enabled": False,
                    "video_recording": False,
                    "setup_steps_are_unscored": True,
                    "fixture_runtime_proof": (
                        "every episode creates the upstream-equivalent 2x2x2 state with absolute "
                        "setblock commands, settles two no-op ticks, and requires eight iron cells "
                        "from the half-open VoxelAction query"
                    ),
                    "privileged_fixture_observer_available_to_policy": False,
                    "oracle": "mine_block.iron_ore delta >= 1 and inventory iron_ore delta >= 1",
                },
                "engine": engine_metadata,
                "task_source": task_metadata,
                "conditioning": {
                    "embedding_seed": NATIVE_EMBEDDING_SEED,
                    "embedding_shape": list(embedding_cpu.shape),
                    "embedding_sha256": embedding_sha256,
                    "projection_shape": list(projected_cpu.shape),
                    "projection_sha256": projection_sha256,
                },
                "episodes": episodes,
                "summary": {
                    "requested_episodes": len(episode_indices),
                    "completed_episodes": len(episodes),
                    "valid_episodes": valid_episodes,
                    "successes": successes,
                    "required_successes": NATIVE_REQUIRED_SUCCESSES if gate_requested else None,
                    "safety_violations": safety_violations,
                    "schema_failures": schema_failures,
                },
                "suite_error": suite_error,
                "timing": {
                    "model_load_seconds": self.load_seconds,
                    "task_embedding_seconds": embedding_seconds,
                    "task_projection_seconds": projection_seconds,
                    "method_seconds": time.perf_counter() - method_started,
                },
                "cuda": {
                    "device_name": self.cuda_device_name,
                    "device_total_memory_bytes": self.cuda_total_memory_bytes,
                    "allocated_bytes": self.torch.cuda.memory_allocated(),
                    "reserved_bytes": self.torch.cuda.memory_reserved(),
                    "peak_allocated_bytes": self.torch.cuda.max_memory_allocated(),
                },
                "infrastructure_acceptance": {
                    "checks": infrastructure_checks,
                    "passed": infrastructure_passed,
                },
                "acceptance": {
                    "evaluated": outcome["evaluated"],
                    "checks": gate_checks,
                    "passed": outcome["passed"],
                },
            },
        )

@app.function(
    image=runtime_image,
    gpu=GPU_TYPE,
    memory=16_384,
    startup_timeout=10 * 60,
    timeout=3 * 60,
    retries=0,
    min_containers=0,
    max_containers=1,
    buffer_containers=0,
    scaledown_window=60,
    single_use_containers=True,
)
def gpu_preflight() -> dict[str, Any]:
    import datasets
    import peft
    import trl
    import tyro  # noqa: F401 - import is the preflight check
    import torch
    import transformers
    import qwen_vl_utils

    _install_minestudio_namespace_shim()
    from minecraftoptimus.model.optimus3.modeling_optimus3 import Optimus3ForConditionalGeneration
    from minecraftoptimus.model.steve1.agent import Optimus3ActionAgent

    properties = torch.cuda.get_device_properties(0)
    return result_envelope(
        "gpu_preflight",
        {
            "cuda_available": torch.cuda.is_available(),
            "cuda_runtime": torch.version.cuda,
            "device_name": properties.name,
            "device_total_memory_bytes": properties.total_memory,
            "torch": torch.__version__,
            "transformers": transformers.__version__,
            "datasets": datasets.__version__,
            "peft": peft.__version__,
            "trl": trl.__version__,
            "tyro": package_version("tyro"),
            "qwen_vl_utils": getattr(qwen_vl_utils, "__version__", "unknown"),
            "optimus3_model_class": Optimus3ForConditionalGeneration.__name__,
            "action_head_class": Optimus3ActionAgent.__name__,
            "attention_implementation": RUNTIME_PINS["attention_implementation"],
        },
    )


@app.cls(
    image=simulator_runtime_image,
    gpu=GPU_TYPE,
    volumes={
        str(MODEL_ROOT): _read_only_volume(model_volume),
        str(SIMULATOR_ROOT): _read_only_volume(simulator_volume),
    },
    cpu=4.0,
    memory=65_536,
    startup_timeout=15 * 60,
    timeout=30 * 60,
    retries=0,
    min_containers=0,
    max_containers=1,
    buffer_containers=0,
    scaledown_window=60,
    single_use_containers=True,
)
class Optimus3Smoke(_NativeEpisodeMixin):
    @modal.enter()
    def load(self) -> None:
        import numpy as np
        import torch
        from transformers import AutoProcessor

        for spec in MODEL_SPECS:
            if not _ready(spec):
                raise RuntimeError(
                    f"missing pinned {spec.key} checkpoint in {MODEL_ROOT}; run cache mode first"
                )

        _install_minestudio_namespace_shim()
        _install_clip_tokenizer_shim()
        from minecraftoptimus.model.optimus3.modeling_optimus3 import Optimus3ForConditionalGeneration
        from minecraftoptimus.model.steve1.agent import Optimus3ActionAgent

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA is not available")
        self.np = np
        self.torch = torch
        self.device = torch.device("cuda")
        properties = torch.cuda.get_device_properties(0)
        self.cuda_device_name = properties.name
        self.cuda_total_memory_bytes = properties.total_memory
        self.load_started = time.perf_counter()
        self.model = Optimus3ForConditionalGeneration.from_pretrained(
            str(_model_path("mllm")),
            attn_implementation=RUNTIME_PINS["attention_implementation"],
            torch_dtype=torch.bfloat16,
            local_files_only=True,
        ).eval()
        self.model.to(self.device)
        self.processor = AutoProcessor.from_pretrained(str(_model_path("mllm")), local_files_only=True)
        self.action_head = Optimus3ActionAgent.from_pretrained(str(_model_path("action_head")))
        # The released .to() calls .to() on MineRLConditionalAgent, which is a
        # wrapper rather than a torch module. Its constructor already selects
        # CUDA and moves each owned module, so verify that placement directly.
        action_modules = {
            "mineclip": self.action_head.mineclip,
            "prior": self.action_head.prior,
            "policy": self.action_head.agent.policy,
            "mllm_embed_linear": self.action_head.mllm_embed_linear,
        }
        for module in action_modules.values():
            module.eval()
        misplaced: list[str] = []
        for module_name, module in action_modules.items():
            for tensor_kind, named_tensors in (
                ("parameter", module.named_parameters()),
                ("buffer", module.named_buffers()),
            ):
                misplaced.extend(
                    f"{module_name}.{tensor_kind}.{name}"
                    for name, tensor in named_tensors
                    if tensor.device.type != self.device.type
                )

        def check_state(path: str, value: Any) -> None:
            if self.torch.is_tensor(value):
                if value.device.type != self.device.type:
                    misplaced.append(path)
            elif isinstance(value, dict):
                for key, item in value.items():
                    check_state(f"{path}.{key}", item)
            elif isinstance(value, (list, tuple)):
                for index, item in enumerate(value):
                    check_state(f"{path}.{index}", item)

        check_state("agent._dummy_first", self.action_head.agent._dummy_first)
        check_state("agent.hidden_state", self.action_head.agent.hidden_state)
        for owner_name, owner_device in (
            ("action_head.device", self.action_head.device),
            ("agent.device", self.action_head.agent.device),
        ):
            if self.torch.device(owner_device).type != self.device.type:
                misplaced.append(owner_name)
        if misplaced:
            raise RuntimeError(f"action-head modules are not on CUDA: {misplaced}")
        self.load_seconds = time.perf_counter() - self.load_started

    def _task_embedding(self, task: str) -> tuple[Any, str]:
        from qwen_vl_utils import process_vision_info
        from minecraftoptimus.utils import TASK2LABEL

        system_prompt = (
            "You are an expert in Minecraft, capable of performing task planning, visual question answering, "
            "reflection, grounding and executing low-level actions."
        )
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": [{"type": "text", "text": task}]},
        ]
        text = self.processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        images, videos = process_vision_info(messages)
        batch = self.processor(
            text=[text],
            images=images,
            videos=videos,
            padding=True,
            return_tensors="pt",
        )
        batch["tasks"] = self.torch.tensor([TASK2LABEL["action"]])
        batch = batch.to(self.device)
        with self.torch.inference_mode():
            generated = self.model.generate(**batch, max_new_tokens=16, do_sample=False)
        trimmed = [output[len(input_ids) :] for input_ids, output in zip(batch.input_ids, generated)]
        raw_label = self.processor.batch_decode(
            trimmed,
            skip_special_tokens=False,
            clean_up_tokenization_spaces=False,
        )[0]
        if not raw_label.endswith("<|im_end|>"):
            raise RuntimeError(f"unexpected action routing output: {raw_label!r}")
        task_label = raw_label[:-10]
        if task_label not in ACTION_LABELS:
            raise RuntimeError(f"unknown action label: {task_label!r}")

        messages.append({"role": "assistant", "content": task_label})
        text = self.processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
        images, videos = process_vision_info(messages)
        batch = self.processor(
            text=[text],
            images=images,
            videos=videos,
            padding=True,
            return_tensors="pt",
        )
        batch["tasks"] = self.torch.tensor([TASK2LABEL["action"]])
        batch["labels"] = batch["input_ids"].clone()
        batch["labels"][(batch["labels"] < 151665) | (batch["labels"] > 151674)] = -100
        batch = batch.to(self.device)
        with self.torch.inference_mode():
            embedding = self.model.get_action_embedding(**batch).float()
        if tuple(embedding.shape) != (1, 1, 3584):
            raise RuntimeError(f"unexpected action embedding shape: {tuple(embedding.shape)}")
        if not self.torch.isfinite(embedding).all():
            raise RuntimeError("action embedding contains non-finite values")
        return embedding, task_label

    def _set_seed(self, seed: int) -> None:
        random.seed(seed)
        self.torch.manual_seed(seed)
        self.torch.cuda.manual_seed_all(seed)
        self.np.random.seed(seed)

    def _reset_action_policy(self, seed: int) -> None:
        self._set_seed(seed)
        self.action_head.agent.reset(self.action_head.text_cond_scale)

    def _project_task_embedding(self, embedding: Any) -> Any:
        projected = self.action_head.mllm_embed_linear(embedding).reshape(embedding.shape[0], -1).contiguous()
        if tuple(projected.shape) != (1, 512):
            raise RuntimeError(f"unexpected projected task shape: {tuple(projected.shape)}")
        if not self.torch.isfinite(projected).all():
            raise RuntimeError("projected task embedding contains non-finite values")
        return projected

    def _cached_equivalent_action(self, projected: Any, frame: Any) -> Mapping[str, Any]:
        goal = self.action_head.prior(projected, deterministic=False)
        minerl_action, _ = self.action_head.agent.get_action({"pov": frame}, goal)
        for key, value in minerl_action.items():
            minerl_action[key] = self.np.array(value.tolist()[0])
        minerl_action["ESC"] = self.np.array(0)
        return minerl_action

    def _timed_action(self, action_call: Callable[[], Mapping[str, Any]]) -> dict[str, Any]:
        self.torch.cuda.synchronize()
        started_ns = time.perf_counter_ns()
        with self.torch.inference_mode():
            raw_action = action_call()
        self.torch.cuda.synchronize()
        policy_finished_ns = time.perf_counter_ns()
        raw_action = validate_complete_action(raw_action)
        applied_action, forbidden_attempts = apply_pilot_safety_mask(raw_action)
        safety_violations = [
            f"allowed action changed: {key}"
            for key in ALLOWED_ACTION_KEYS
            if applied_action[key] != raw_action[key]
        ]
        active_applied = set(active_action_keys(applied_action))
        safety_violations.extend(
            f"forbidden action survived mask: {key}"
            for key in FORBIDDEN_ACTION_KEYS
            if key in active_applied
        )
        finished_ns = time.perf_counter_ns()
        return {
            "policy_call_ms": (policy_finished_ns - started_ns) / 1_000_000.0,
            "safety_ms": (finished_ns - policy_finished_ns) / 1_000_000.0,
            "native_step_ms": (finished_ns - started_ns) / 1_000_000.0,
            "raw_action": raw_action,
            "applied_action": applied_action,
            "forbidden_attempts": forbidden_attempts,
            "safety_violations": safety_violations,
        }

    @staticmethod
    def _summarize_records(records: list[dict[str, Any]]) -> dict[str, Any] | None:
        if not records:
            return None
        return {
            "policy_call_ms": latency_summary([record["policy_call_ms"] for record in records]),
            "native_step_ms": latency_summary([record["native_step_ms"] for record in records]),
        }

    @staticmethod
    def _action_evidence(records: list[dict[str, Any]]) -> dict[str, Any]:
        raw_active_counts = {key: 0 for key in ALL_ACTION_KEYS}
        applied_active_counts = {key: 0 for key in ALL_ACTION_KEYS}
        forbidden_attempt_counts = {key: 0 for key in FORBIDDEN_ACTION_KEYS}
        safety_violations: list[dict[str, Any]] = []
        for index, record in enumerate(records):
            for key in active_action_keys(record["raw_action"]):
                raw_active_counts[key] += 1
            for key in active_action_keys(record["applied_action"]):
                applied_active_counts[key] += 1
            for key in record["forbidden_attempts"]:
                forbidden_attempt_counts[key] += 1
            safety_violations.extend(
                {
                    "phase": record.get("phase", "unknown"),
                    "index": record.get("index", index),
                    "message": message,
                }
                for message in record["safety_violations"]
            )
        return {
            "raw_active_counts": raw_active_counts,
            "applied_active_counts": applied_active_counts,
            "forbidden_attempt_counts": forbidden_attempt_counts,
            "safety_violations": safety_violations,
        }

    @modal.method()
    def run(self, task: str = DEFAULT_TASK, seed: int = 7) -> dict[str, Any]:
        task = validate_task(task)
        seed = validate_seed(seed)
        self._set_seed(seed)

        embedding_started = time.perf_counter()
        embedding, task_label = self._task_embedding(task)
        self.torch.cuda.synchronize()
        embedding_seconds = time.perf_counter() - embedding_started

        embedding_cpu = embedding.detach().cpu().numpy()
        embedding_sha256 = hashlib.sha256(embedding_cpu.tobytes()).hexdigest()
        frame = self.np.full(FRAME_SHAPE, 127, dtype=self.np.uint8)
        action_started = time.perf_counter()
        with self.torch.inference_mode():
            raw_action, _ = self.action_head.optimus3_action(embedding, frame, task=task)
        self.torch.cuda.synchronize()
        action_seconds = time.perf_counter() - action_started
        raw_action = validate_complete_action(raw_action)
        applied_action, forbidden_attempts = apply_pilot_safety_mask(raw_action)

        return result_envelope(
            "model_action_smoke",
            {
                "task": task,
                "task_label": task_label,
                "seed": seed,
                "frame": {"shape": list(FRAME_SHAPE), "fill": 127},
                "embedding": {
                    "shape": list(embedding_cpu.shape),
                    "sha256": embedding_sha256,
                    "minimum": float(embedding_cpu.min()),
                    "maximum": float(embedding_cpu.max()),
                    "mean": float(embedding_cpu.mean()),
                    "finite": True,
                },
                "raw_action": raw_action,
                "applied_action": applied_action,
                "forbidden_attempts": forbidden_attempts,
                "timing": {
                    "model_load_seconds": self.load_seconds,
                    "task_embedding_seconds": embedding_seconds,
                    "single_action_seconds": action_seconds,
                },
                "cuda": {
                    "allocated_bytes": self.torch.cuda.memory_allocated(),
                    "reserved_bytes": self.torch.cuda.memory_reserved(),
                    "peak_allocated_bytes": self.torch.cuda.max_memory_allocated(),
                },
            },
        )

    @modal.method()
    def bench(
        self,
        task: str = DEFAULT_TASK,
        seed: int = 7,
        reference_steps: int = DEFAULT_REFERENCE_STEPS,
        warmup_steps: int = DEFAULT_WARMUP_STEPS,
        measured_steps: int = DEFAULT_MEASURED_STEPS,
    ) -> dict[str, Any]:
        task = validate_task(task)
        seed = validate_seed(seed)
        reference_steps, warmup_steps, measured_steps = validate_benchmark_steps(
            reference_steps,
            warmup_steps,
            measured_steps,
        )
        method_started = time.perf_counter()
        wall_deadline = method_started + BENCHMARK_WALL_BUDGET_SECONDS
        self._set_seed(seed)

        embedding_started = time.perf_counter()
        embedding, task_label = self._task_embedding(task)
        self.torch.cuda.synchronize()
        embedding_seconds = time.perf_counter() - embedding_started
        embedding_cpu = embedding.detach().cpu().numpy()

        self.torch.cuda.synchronize()
        projection_started = time.perf_counter()
        with self.torch.inference_mode():
            projected = self._project_task_embedding(embedding)
        self.torch.cuda.synchronize()
        projection_seconds = time.perf_counter() - projection_started
        projected_cpu = projected.detach().cpu().numpy()
        frame = self.np.full(FRAME_SHAPE, 127, dtype=self.np.uint8)

        validation_errors: list[dict[str, Any]] = []
        recurrent_reset_events: list[str] = []

        def collect_phase(
            phase: str,
            count: int,
            action_call: Callable[[], Mapping[str, Any]],
        ) -> tuple[list[dict[str, Any]], str | None]:
            records: list[dict[str, Any]] = []
            for index in range(count):
                if time.perf_counter() >= wall_deadline:
                    return records, "wall_budget_exhausted"
                try:
                    record = self._timed_action(action_call)
                except (TypeError, ValueError) as error:
                    validation_errors.append({"phase": phase, "index": index, "message": str(error)})
                    return records, "action_validation_failed"
                record["phase"] = phase
                record["index"] = index
                records.append(record)
            return records, None

        self._reset_action_policy(seed)
        recurrent_reset_events.append("upstream_reference")
        reference_records, status = collect_phase(
            "upstream_reference",
            reference_steps,
            lambda: self.action_head.optimus3_action(embedding, frame, task=task)[0],
        )

        warmup_records: list[dict[str, Any]] = []
        measured_records: list[dict[str, Any]] = []
        if status is None:
            self._reset_action_policy(seed)
            recurrent_reset_events.append("cached_projection_before_warmup")
            warmup_records, status = collect_phase(
                "cached_projection_warmup",
                warmup_steps,
                lambda: self._cached_equivalent_action(projected, frame),
            )
        if status is None:
            self.torch.cuda.reset_peak_memory_stats()
            measured_records, status = collect_phase(
                "cached_projection_measured",
                measured_steps,
                lambda: self._cached_equivalent_action(projected, frame),
            )
        status = status or "complete"

        reference_summary = self._summarize_records(reference_records)
        warmup_summary = self._summarize_records(warmup_records)
        measured_summary = self._summarize_records(measured_records)
        all_evidence = self._action_evidence(reference_records + warmup_records + measured_records)
        measured_evidence = self._action_evidence(measured_records)
        latency_checks = (
            latency_gate(measured_summary["native_step_ms"])
            if measured_summary is not None
            else {
                "p95_within_deadline": False,
                "miss_rate_below_five_percent": False,
                "passed": False,
            }
        )
        checks = {
            "status_complete": status == "complete",
            "requested_gpu_present": GPU_TYPE.lower() in self.cuda_device_name.lower(),
            "torch_runtime_matches_pin": self.torch.__version__ == RUNTIME_PINS["torch"],
            "cuda_runtime_matches_pin": self.torch.version.cuda == RUNTIME_PINS["cuda"],
            "pinned_checkpoints_ready": all(_ready(spec) for spec in MODEL_SPECS),
            "embedding_shape_valid": list(embedding_cpu.shape) == [1, 1, 3584],
            "projection_shape_valid": list(projected_cpu.shape) == [1, 512],
            "default_task_routes_to_iron": task != DEFAULT_TASK or task_label == "<iron>",
            "reference_steps_complete": len(reference_records) == reference_steps,
            "warmup_steps_complete": len(warmup_records) == warmup_steps,
            "measured_steps_complete": len(measured_records) == measured_steps,
            "minimum_measured_steps": len(measured_records) >= 200,
            "p95_within_50_ms": latency_checks["p95_within_deadline"],
            "deadline_miss_rate_below_five_percent": latency_checks["miss_rate_below_five_percent"],
            "raw_action_schema_valid": not validation_errors,
            "safety_mask_violations_zero": not all_evidence["safety_violations"],
            "recurrent_reset_sequence_valid": recurrent_reset_events
            == ["upstream_reference", "cached_projection_before_warmup"],
            "single_cached_recurrent_stream": (
                len(warmup_records) + len(measured_records) == warmup_steps + measured_steps
            ),
        }
        sequence_digest = hashlib.sha256()
        for record in measured_records:
            sequence_digest.update(
                json.dumps(record["applied_action"], sort_keys=True, separators=(",", ":")).encode("utf-8")
            )
        unadjusted_latency_ratio = None
        if reference_summary is not None and measured_summary is not None:
            unadjusted_latency_ratio = (
                reference_summary["native_step_ms"]["mean_ms"]
                / measured_summary["native_step_ms"]["mean_ms"]
            )

        return result_envelope(
            "model_action_latency_benchmark",
            {
                "status": status,
                "task": task,
                "task_label": task_label,
                "seed": seed,
                "protocol": {
                    "scope": "in_container_compute_loop",
                    "frame": {"shape": list(FRAME_SHAPE), "fill": 127},
                    "reference_steps": reference_steps,
                    "warmup_steps": warmup_steps,
                    "measured_steps": measured_steps,
                    "deadline_ms": TARGET_LATENCY_MS,
                    "wall_budget_seconds": BENCHMARK_WALL_BUDGET_SECONDS,
                    "synchronize_each_step": True,
                    "recurrent_resets": {
                        "upstream_reference": 1,
                        "cached_projection_before_warmup": 1,
                        "between_warmup_and_measurement": 0,
                        "observed_events": recurrent_reset_events,
                    },
                    "timed_step_includes": [
                        "stochastic_prior",
                        "frame_resize_and_host_to_device_transfer",
                        "classifier_free_guidance_recurrent_policy",
                        "stochastic_action_sampling",
                        "device_to_host_action_mapping",
                        "fail_closed_action_validation",
                        "normalization",
                        "safety_mask",
                    ],
                    "timed_step_excludes": [
                        "model_load",
                        "mllm_task_embedding",
                        "deterministic_task_projection",
                        "minecraft_frame_capture",
                        "network_transport",
                        "tick_scheduling",
                        "minecraft_action_application",
                    ],
                },
                "embedding": {
                    "shape": list(embedding_cpu.shape),
                    "sha256": hashlib.sha256(embedding_cpu.tobytes()).hexdigest(),
                    "finite": True,
                },
                "cached_projection": {
                    "shape": list(projected_cpu.shape),
                    "sha256": hashlib.sha256(projected_cpu.tobytes()).hexdigest(),
                    "finite": True,
                    "intended_distribution_equivalence": True,
                    "distribution_equivalence_basis": "pinned_source_path_audit",
                    "distribution_equivalence_statistically_tested": False,
                    "same_seed_trace_equivalent": False,
                    "per_frame_stochastic_prior_preserved": True,
                },
                "upstream_reference": {
                    "description": "released optimus3_action including dead per-step MineCLIP shape lookup",
                    "summary": reference_summary,
                    "samples": reference_records,
                },
                "cached_projection_warmup": {
                    "summary": warmup_summary,
                    "samples": warmup_records,
                },
                "cached_projection_measured": {
                    "summary": measured_summary,
                    "samples": measured_records,
                    "action_evidence": measured_evidence,
                    "applied_action_sequence_sha256": sequence_digest.hexdigest(),
                },
                "comparison": {
                    "unadjusted_reference_to_cached_mean_latency_ratio": unadjusted_latency_ratio,
                    "warm_state_matched": False,
                    "acceptance_uses_comparison": False,
                },
                "validation_errors": validation_errors,
                "all_phase_safety_evidence": all_evidence,
                "timing": {
                    "model_load_seconds": self.load_seconds,
                    "task_embedding_seconds": embedding_seconds,
                    "task_projection_seconds": projection_seconds,
                    "method_seconds": time.perf_counter() - method_started,
                },
                "cuda": {
                    "device_name": self.cuda_device_name,
                    "device_total_memory_bytes": self.cuda_total_memory_bytes,
                    "torch": self.torch.__version__,
                    "cuda_runtime": self.torch.version.cuda,
                    "allocated_bytes": self.torch.cuda.memory_allocated(),
                    "reserved_bytes": self.torch.cuda.memory_reserved(),
                    "peak_allocated_bytes_since_measurement": self.torch.cuda.max_memory_allocated(),
                },
                "acceptance": {"checks": checks, "passed": all(checks.values())},
            },
        )

    @modal.method()
    def native_episode(self, episode_index: int = 0) -> dict[str, Any]:
        return self._run_native([validate_episode_index(episode_index)])

    @modal.method()
    def native_suite(self) -> dict[str, Any]:
        return self._run_native(list(range(NATIVE_EPISODE_COUNT)))


def _emit(result: dict[str, Any], output: str) -> None:
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if output:
        path = Path(output).expanduser().resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered, encoding="utf-8")
        print(f"result: {path}")
    else:
        print(rendered, end="")


@app.local_entrypoint()
def main(
    mode: str = "preflight",
    task: str = DEFAULT_TASK,
    seed: int = 7,
    episode_index: int = 0,
    reference_steps: int = DEFAULT_REFERENCE_STEPS,
    warmup_steps: int = DEFAULT_WARMUP_STEPS,
    measured_steps: int = DEFAULT_MEASURED_STEPS,
    output: str = "",
) -> None:
    if mode == "preflight":
        result = gpu_preflight.remote()
    elif mode == "cache":
        result = cache_weights.remote()
    elif mode == "engine-cache":
        result = cache_simulator_engine.remote()
    elif mode == "sim-preflight":
        result = simulator_preflight.remote()
    elif mode == "smoke":
        result = Optimus3Smoke().run.remote(validate_task(task), validate_seed(seed))
    elif mode == "bench":
        counts = validate_benchmark_steps(reference_steps, warmup_steps, measured_steps)
        result = Optimus3Smoke().bench.remote(validate_task(task), validate_seed(seed), *counts)
    elif mode == "episode":
        result = Optimus3Smoke().native_episode.remote(validate_episode_index(episode_index))
    elif mode == "episodes":
        result = Optimus3Smoke().native_suite.remote()
    else:
        raise ValueError(
            "mode must be one of: preflight, cache, engine-cache, sim-preflight, smoke, bench, episode, episodes"
        )
    _emit(result, output)
