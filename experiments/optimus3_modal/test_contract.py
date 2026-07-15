from __future__ import annotations

import hashlib
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


class ScalarLike:
    def __init__(self, value):
        self.value = value

    def item(self):
        return self.value


class PredicateSpace:
    def __init__(self, predicate):
        self.predicate = predicate

    def contains(self, value):
        return self.predicate(value)


class ContractTest(unittest.TestCase):
    def test_manifest_pins_immutable_revisions_without_secrets(self):
        manifest = contract.pilot_manifest()
        revisions = [
            manifest["source"]["optimus3_revision"],
            manifest["source"]["llama_factory_revision"],
            manifest["simulator_engine"]["revision"],
            *(model["revision"] for model in manifest["models"]),
        ]
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{40}", revision) for revision in revisions))
        self.assertGreater(manifest["expected_model_bytes"], 22_000_000_000)
        self.assertEqual(set(manifest["uploaded_source_sha256"]), {"contract.py", "modal_app.py"})
        self.assertTrue(
            all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in manifest["uploaded_source_sha256"].values())
        )
        rendered = json.dumps(manifest).lower()
        self.assertNotIn("token_secret", rendered)
        self.assertNotIn("hf_token", rendered)

        native_gate = manifest["native_episode_gate"]
        self.assertEqual(native_gate["name"], "native_simple_mine_iron_v1")
        self.assertEqual(native_gate["episode_count"], 10)
        self.assertEqual(native_gate["required_successes"], 8)
        self.assertIn("not upstream-prompt parity", native_gate["claim_scope"])
        self.assertIn("visibility is not guaranteed", native_gate["claim_scope"])
        self.assertEqual(manifest["simulator_engine"]["expected_bytes"], 458_106_630)
        self.assertRegex(manifest["simulator_engine"]["sha256"], r"^[0-9a-f]{64}$")

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

    def test_benchmark_step_validation_is_bounded(self):
        self.assertEqual(contract.validate_benchmark_steps(8, 32, 256), (8, 32, 256))
        for counts in (
            (0, 32, 256),
            (8, 0, 256),
            (8, 32, 199),
            (33, 32, 256),
            (8, 257, 256),
            (32, 128, 512),
        ):
            with self.assertRaises(ValueError):
                contract.validate_benchmark_steps(*counts)
        with self.assertRaises(TypeError):
            contract.validate_benchmark_steps(True, 32, 256)

    def test_native_episode_seed_schedule_is_locked(self):
        upstream_bytes = ("\n".join(contract.NATIVE_UPSTREAM_TASK_COMMANDS) + "\n").encode("utf-8")
        self.assertEqual(
            hashlib.sha256(upstream_bytes).hexdigest(),
            contract.NATIVE_UPSTREAM_TASK_COMMANDS_SHA256,
        )
        fixture_bytes = json.dumps(
            contract.native_fixture_spec(),
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        self.assertEqual(hashlib.sha256(fixture_bytes).hexdigest(), contract.NATIVE_FIXTURE_SPEC_SHA256)
        self.assertEqual(contract.NATIVE_FIXTURE_VOXEL_BOUNDS, (2, 4, 0, 2, 2, 4))
        self.assertEqual(contract.NATIVE_FIXTURE_MAX_MATERIALIZATION_PASSES, 3)
        x0, x1, y0, y1, z0, z1 = contract.NATIVE_FIXTURE_VOXEL_BOUNDS
        self.assertEqual((x1 - x0) * (y1 - y0) * (z1 - z0), contract.NATIVE_EXPECTED_IRON_BLOCKS)
        self.assertEqual(
            set(contract.NATIVE_FIXTURE_BLOCK_OFFSETS),
            {(x, y, z) for x in range(x0, x1) for y in range(y0, y1) for z in range(z0, z1)},
        )
        self.assertEqual(
            contract.NATIVE_WORLD_SEEDS,
            (
                1189277871,
                1054978500,
                2643425111,
                303169024,
                387523688,
                3565273368,
                4077019064,
                1055531191,
                2188313013,
                2516925590,
            ),
        )
        self.assertEqual(
            contract.NATIVE_POLICY_SEEDS,
            (
                755769072,
                2217087932,
                3499080866,
                257376347,
                371041615,
                3684550301,
                2513634747,
                3036068752,
                352040951,
                4028083782,
            ),
        )
        self.assertEqual(contract.native_episode_seeds(0), (1189277871, 755769072))
        with self.assertRaises(ValueError):
            contract.validate_episode_index(10)
        with self.assertRaises(TypeError):
            contract.validate_episode_index(True)

    def test_native_episode_oracle_requires_mining_and_collection(self):
        baseline = {"inventory_iron_ore": 2, "mine_iron_ore": 3}
        inventory = {
            0: {"type": "minecraft:stone_pickaxe", "quantity": 1},
            1: {"type": "minecraft:iron_ore", "quantity": 3},
        }
        self.assertEqual(contract.inventory_quantity(inventory, "iron_ore"), 3)
        self.assertFalse(
            contract.native_episode_success(
                {"inventory": inventory, "mine_block": {"iron_ore": 3}},
                baseline,
            )
        )
        self.assertFalse(
            contract.native_episode_success(
                {
                    "inventory": {1: {"type": "iron_ore", "quantity": 2}},
                    "mine_block": {"iron_ore": 4},
                },
                baseline,
            )
        )
        self.assertTrue(
            contract.native_episode_success(
                {"inventory": inventory, "mine_block": {"iron_ore": ScalarLike(4)}},
                baseline,
            )
        )

    def test_native_suite_gate_distinguishes_failure_from_invalidity(self):
        def episodes(successes, valid=True, count=10):
            return [
                {
                    "episode_index": index,
                    "valid": valid,
                    "success": index < successes,
                }
                for index in range(count)
            ]

        passing = contract.native_suite_outcome(episodes(8), infrastructure_passed=True)
        self.assertTrue(passing["evaluated"])
        self.assertTrue(passing["passed"])

        competence_failure = contract.native_suite_outcome(episodes(7), infrastructure_passed=True)
        self.assertTrue(competence_failure["evaluated"])
        self.assertFalse(competence_failure["passed"])

        invalid = contract.native_suite_outcome(episodes(8, valid=False), infrastructure_passed=False)
        self.assertFalse(invalid["evaluated"])
        self.assertIsNone(invalid["passed"])
        self.assertEqual(invalid["successes"], 0)

        incomplete = contract.native_suite_outcome(episodes(8, count=9), infrastructure_passed=True)
        self.assertFalse(incomplete["evaluated"])
        self.assertIsNone(incomplete["passed"])

        single = contract.native_suite_outcome(episodes(1, count=1), infrastructure_passed=True)
        self.assertFalse(single["evaluated"])
        self.assertIsNone(single["passed"])

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
        self.assertEqual(
            contract.active_action_keys(normalized),
            ["attack", "camera", "forward", "hotbar.4", "inventory"],
        )

        complete = {key: normalized[key] for key in contract.POLICY_ACTION_KEYS}
        self.assertEqual(contract.validate_complete_action(complete), complete)
        self.assertEqual(len(complete), 22)
        policy_applied, _ = contract.apply_pilot_safety_mask(complete)
        self.assertEqual(len(policy_applied), 24)
        self.assertEqual(policy_applied["pickItem"], 0)
        self.assertEqual(policy_applied["swapHands"], 0)
        motor_controls = contract.native_motor_controls(policy_applied)
        self.assertEqual(set(motor_controls), set(contract.ALLOWED_ACTION_KEYS))
        self.assertEqual(motor_controls["forward"], 1)
        with self.assertRaises(ValueError):
            contract.native_motor_controls({**policy_applied, "use": 1})
        with self.assertRaises(ValueError):
            contract.native_motor_controls({key: value for key, value in policy_applied.items() if key != "use"})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({"forward": 1})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({**complete, "unexpected": 1})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({**complete, "pickItem": 0})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({**complete, 1: 0})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({**complete, "forward": [1]})
        with self.assertRaises(ValueError):
            contract.validate_complete_action({**complete, "forward": 2})

    def test_simulator_action_evidence_captures_exact_noop_overlay(self):
        noop = {
            "attack": 0,
            "camera": ArrayLike([0.0, 0.0]),
            "forward": 0,
            "chat": "",
        }
        sent = {
            "attack": 0,
            "camera": ArrayLike([0.0, 0.0]),
            "forward": ScalarLike(1),
            "chat": "",
        }
        evidence = contract.simulator_action_evidence(noop, sent)
        self.assertEqual(evidence["changed_keys"], ["forward"])
        self.assertEqual(evidence["active_keys"], ["forward"])
        self.assertEqual(evidence["active_motor_keys"], ["forward"])
        self.assertEqual(evidence["canonical_action"]["forward"], 1)
        self.assertRegex(evidence["sha256"], r"^[0-9a-f]{64}$")

        with self.assertRaisesRegex(ValueError, "non-motor simulator controls changed"):
            contract.simulator_action_evidence(noop, {**sent, "chat": "hello"})
        with self.assertRaisesRegex(ValueError, "non-motor simulator controls are active"):
            contract.simulator_action_evidence(
                {**noop, "chat": "already-active"},
                {**noop, "chat": "already-active"},
            )
        with self.assertRaisesRegex(ValueError, "simulator action envelope mismatch"):
            contract.simulator_action_evidence(
                noop,
                {key: value for key, value in sent.items() if key != "chat"},
            )

    def test_simulator_motor_membership_validates_each_allowed_control(self):
        action = {
            key: [0.0, 0.0] if key == "camera" else 0
            for key in contract.ALLOWED_ACTION_KEYS
        }
        spaces = {
            key: PredicateSpace(
                (lambda value: isinstance(value, list) and len(value) == 2)
                if key == "camera"
                else (lambda value: type(value) is int and value in (0, 1))
            )
            for key in contract.ALLOWED_ACTION_KEYS
        }
        membership = contract.validate_simulator_motor_membership(action, spaces)
        self.assertEqual(set(membership), set(contract.ALLOWED_ACTION_KEYS))
        self.assertTrue(all(membership.values()))

        with self.assertRaisesRegex(ValueError, "outside their spaces"):
            contract.validate_simulator_motor_membership({**action, "forward": 2}, spaces)
        with self.assertRaisesRegex(ValueError, "missing_spaces"):
            contract.validate_simulator_motor_membership(
                action,
                {key: space for key, space in spaces.items() if key != "attack"},
            )

    def test_latency_summary_and_gate(self):
        summary = contract.latency_summary([10, 20, 30, 40, 60], deadline_ms=50)
        self.assertEqual(summary["count"], 5)
        self.assertEqual(summary["mean_ms"], 32)
        self.assertEqual(summary["p95_ms"], 60)
        self.assertEqual(summary["deadline_misses"], 1)
        self.assertAlmostEqual(summary["deadline_miss_rate"], 0.2)
        self.assertFalse(contract.latency_gate(summary)["passed"])

        passing = contract.latency_summary([20] * 100, deadline_ms=50)
        self.assertTrue(contract.latency_gate(passing)["passed"])

        exactly_five_percent_missed = contract.latency_summary([20] * 95 + [60] * 5, deadline_ms=50)
        self.assertEqual(exactly_five_percent_missed["p95_ms"], 20)
        self.assertEqual(exactly_five_percent_missed["deadline_miss_rate"], 0.05)
        self.assertFalse(contract.latency_gate(exactly_five_percent_missed)["passed"])
        with self.assertRaises(ValueError):
            contract.latency_summary([])

    def test_result_envelope_is_serializable(self):
        result = contract.result_envelope("gpu_preflight", {"ok": True, "values": ArrayLike([1, 2])})
        rendered = json.dumps(result)
        self.assertIn('"kind": "gpu_preflight"', rendered)
        self.assertEqual(result["payload"]["values"], [1, 2])


if __name__ == "__main__":
    unittest.main()
