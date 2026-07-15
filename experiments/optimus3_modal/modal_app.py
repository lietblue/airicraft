"""Ephemeral Modal GPU app for the first Optimus-3 motor-policy smoke."""

from __future__ import annotations

import hashlib
import json
import sys
import time
import types
from importlib.metadata import version as package_version
from pathlib import Path
from typing import Any

import modal

from contract import (
    ACTION_LABELS,
    APP_NAME,
    DEFAULT_TASK,
    FRAME_SHAPE,
    GPU_TYPE,
    LLAMA_FACTORY_REPOSITORY,
    LLAMA_FACTORY_REVISION,
    MODEL_SPECS,
    OPTIMUS3_REPOSITORY,
    OPTIMUS3_REVISION,
    RUNTIME_PINS,
    VOLUME_NAME,
    apply_pilot_safety_mask,
    model_spec,
    normalize_action,
    result_envelope,
    validate_seed,
    validate_task,
)


MODEL_ROOT = Path("/models")
SOURCE_ROOT = Path("/opt/optimus3")
READY_MARKER = ".airicraft-ready.json"
LOCAL_CONTRACT_PATH = Path(__file__).with_name("contract.py")
LOCAL_APP_PATH = Path(__file__)

app = modal.App(APP_NAME, include_source=False)
model_volume = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)


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

runtime_image = (
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


def _install_minestudio_namespace_shim() -> None:
    """Avoid importing the Java simulator when only its static action map is needed."""
    simulator_path = SOURCE_ROOT / "MineStudio" / "minestudio" / "simulator"
    module = types.ModuleType("minestudio.simulator")
    module.__path__ = [str(simulator_path)]
    module.__package__ = "minestudio.simulator"
    sys.modules["minestudio.simulator"] = module


def _install_clip_tokenizer_shim() -> None:
    from transformers import AutoTokenizer
    import minestudio.utils.mineclip_lib.mineclip.tokenization as mineclip_tokenization

    tokenizer = AutoTokenizer.from_pretrained(str(_model_path("clip_tokenizer")), local_files_only=True)
    mineclip_tokenization.get_tokenizer = lambda _name, use_fast=True: tokenizer


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
    import tyro
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
    image=runtime_image,
    gpu=GPU_TYPE,
    volumes={str(MODEL_ROOT): _read_only_volume(model_volume)},
    memory=65_536,
    startup_timeout=10 * 60,
    timeout=5 * 60,
    retries=0,
    min_containers=0,
    max_containers=1,
    buffer_containers=0,
    scaledown_window=60,
    single_use_containers=True,
)
class Optimus3Smoke:
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

    @modal.method()
    def run(self, task: str = DEFAULT_TASK, seed: int = 7) -> dict[str, Any]:
        task = validate_task(task)
        seed = validate_seed(seed)
        self.torch.manual_seed(seed)
        self.torch.cuda.manual_seed_all(seed)
        self.np.random.seed(seed)

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
        raw_action = normalize_action(raw_action)
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


def _emit(result: dict[str, Any], output: str) -> None:
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if output:
        path = Path(output).expanduser().resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered, encoding="utf-8")
        print(f"result: {path}")
    print(rendered, end="")


@app.local_entrypoint()
def main(mode: str = "preflight", task: str = DEFAULT_TASK, seed: int = 7, output: str = "") -> None:
    if mode == "preflight":
        result = gpu_preflight.remote()
    elif mode == "cache":
        result = cache_weights.remote()
    elif mode == "smoke":
        result = Optimus3Smoke().run.remote(validate_task(task), validate_seed(seed))
    else:
        raise ValueError("mode must be one of: preflight, cache, smoke")
    _emit(result, output)
