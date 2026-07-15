from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path


EXPERIMENT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(EXPERIMENT_DIR))

import contract  # noqa: E402


class ShapedFrame:
    shape = contract.FRAME_SHAPE


class ArrayLike:
    def __init__(self, value):
        self.value = value

    def tolist(self):
        return self.value


class ContractTest(unittest.TestCase):
    def test_manifest_pins_immutable_revisions_without_secrets(self):
        manifest = contract.pilot_manifest()
        revisions = [
            manifest["source"]["optimus3_revision"],
            manifest["source"]["llama_factory_revision"],
            *(model["revision"] for model in manifest["models"]),
        ]
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{40}", revision) for revision in revisions))
        self.assertGreater(manifest["expected_model_bytes"], 22_000_000_000)
        rendered = json.dumps(manifest).lower()
        self.assertNotIn("token_secret", rendered)
        self.assertNotIn("hf_token", rendered)

    def test_task_and_seed_validation(self):
        self.assertEqual(contract.validate_task(" collect   one iron ore "), "collect one iron ore")
        self.assertEqual(contract.validate_seed(7), 7)
        for task in ("", "   ", "bad\ninput", "x" * 257):
            with self.assertRaises(ValueError):
                contract.validate_task(task)
        for seed in (-1, 2**32):
            with self.assertRaises(ValueError):
                contract.validate_seed(seed)
        with self.assertRaises(TypeError):
            contract.validate_seed(True)

    def test_frame_shape_validation(self):
        self.assertEqual(contract.validate_frame_shape(ShapedFrame()), contract.FRAME_SHAPE)
        with self.assertRaises(ValueError):
            contract.validate_frame_shape([[[0]]])

    def test_action_normalization_and_safety_mask(self):
        raw = {
            "camera": ArrayLike([1.25, -2.5]),
            "forward": ArrayLike(1),
            "attack": 1,
            "inventory": ArrayLike(1),
            "hotbar.4": 1,
            "use": 0,
        }
        normalized = contract.normalize_action(raw)
        self.assertEqual(normalized["camera"], [1.25, -2.5])
        self.assertEqual(normalized["forward"], 1)
        applied, attempted = contract.apply_pilot_safety_mask(normalized)
        self.assertEqual(applied["forward"], 1)
        self.assertEqual(applied["attack"], 1)
        self.assertEqual(applied["inventory"], 0)
        self.assertEqual(applied["hotbar.4"], 0)
        self.assertEqual(attempted, ["hotbar.4", "inventory"])

    def test_result_envelope_is_serializable(self):
        result = contract.result_envelope("gpu_preflight", {"ok": True, "values": ArrayLike([1, 2])})
        rendered = json.dumps(result)
        self.assertIn('"kind": "gpu_preflight"', rendered)
        self.assertEqual(result["payload"]["values"], [1, 2])


if __name__ == "__main__":
    unittest.main()
