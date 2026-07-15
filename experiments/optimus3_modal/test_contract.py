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
        self.assertEqual(
            manifest["tasks"],
            {
                "synthetic_benchmark": contract.DEFAULT_TASK,
                "native_episode_gate": contract.NATIVE_POLICY_PROMPT,
            },
        )

        native_gate = manifest["native_episode_gate"]
        self.assertEqual(native_gate["name"], "native_simple_mine_iron_v2_parity")
        self.assertEqual(native_gate["episode_count"], 10)
        self.assertEqual(native_gate["required_successes"], 8)
        self.assertEqual(native_gate["policy_prompt"], contract.NATIVE_UPSTREAM_TASK_TEXT)
        self.assertEqual(
            native_gate["attack_stabilizer"]["zeroed_controls"],
            list(contract.OFFICIAL_ATTACK_STABILIZED_KEYS),
        )
        self.assertEqual(
            native_gate["environment"]["policy_view_settle_noop_steps"],
            contract.NATIVE_POLICY_VIEW_SETTLE_STEPS,
        )
        self.assertTrue(native_gate["fresh_simulator_process_between_episodes"])
        self.assertTrue(native_gate["video_recording"])
        self.assertEqual(native_gate["capture_wall_budget_seconds"], 180.0)
        self.assertEqual(native_gate["outer_hard_timeout_seconds"], 2100)
        self.assertEqual(
            manifest["native_diagnostic_episode"]["seed_namespace"],
            contract.LEGACY_NATIVE_SEED_NAMESPACE,
        )
        self.assertEqual(manifest["simulator_engine"]["expected_bytes"], 458_106_630)
        self.assertRegex(manifest["simulator_engine"]["sha256"], r"^[0-9a-f]{64}$")

        replay = manifest["native_failure_replay"]
        self.assertEqual(replay["episode_indices"], [1, 2, 3, 5, 6])
        self.assertEqual(replay["frame_shape"], [128, 128, 3])
        self.assertEqual(replay["frames_per_timeout_episode"], 201)
        self.assertEqual(replay["video_codec"], "h264")
        self.assertFalse(replay["policy_or_environment_inputs_changed"])
        self.assertTrue(
            all(re.fullmatch(r"[0-9a-f]{64}", value) for value in replay["expected_trace_sha256"].values())
        )
        self.assertTrue(
            all(
                re.fullmatch(r"[0-9a-f]{64}", value)
                for value in replay["expected_applied_action_sha256"].values()
            )
        )

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
        environment_bytes = ("\n".join(contract.NATIVE_ENVIRONMENT_COMMANDS) + "\n").encode(
            "utf-8"
        )
        self.assertEqual(
            hashlib.sha256(environment_bytes).hexdigest(),
            contract.NATIVE_ENVIRONMENT_COMMANDS_SHA256,
        )
        self.assertEqual(contract.NATIVE_POLICY_PROMPT, contract.NATIVE_UPSTREAM_TASK_TEXT)
        self.assertEqual(contract.NATIVE_POLICY_VIEW_SETTLE_STEPS, 220)
        self.assertEqual(
            contract.NATIVE_RUNTIME_TEMPLATE_SHA256,
            {
                "options.txt": "f55c7a867d5ea1309cf13e3def7a25a71ac73f5948f2d5580af3905210644a6f",
                "optionsof.txt": "c4d755753b6b4dcd7137119895b778de1aef941b87f246418a6c845e1be557ee",
            },
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
                1393141024,
                851357790,
                3842314870,
                2057505974,
                1796445709,
                3959490677,
                4056707612,
                2867472100,
                4128692959,
                121629994,
            ),
        )
        self.assertEqual(
            contract.NATIVE_POLICY_SEEDS,
            (
                3345470550,
                309602449,
                3950613036,
                2381837806,
                4107765080,
                421881608,
                1811199394,
                1370296776,
                2564906271,
                3032025746,
            ),
        )
        self.assertEqual(contract.native_episode_seeds(0), (1393141024, 3345470550))
        self.assertEqual(
            contract.native_diagnostic_episode_seeds(6),
            (4077019064, 2513634747),
        )
        self.assertEqual(contract.LEGACY_NATIVE_WORLD_SEEDS[6], 4077019064)
        self.assertEqual(contract.LEGACY_NATIVE_POLICY_SEEDS[6], 2513634747)
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

    def test_applied_action_sequence_digest_is_stable(self):
        trace = [
            {"step": 1, "applied_action": {"forward": 1, "camera": [0.0, 0.0]}},
            {"step": 2, "applied_action": {"camera": [1.5, -2.0], "attack": 1}},
        ]
        self.assertEqual(
            contract.applied_action_sequence_sha256(trace),
            "adeccad32882bafc345a2baa0f05f7c6bfd46e2ae3764f328a31cdbe53e952ba",
        )
        self.assertEqual(
            contract.applied_action_sequence_sha256([{}, *trace]),
            contract.applied_action_sequence_sha256(trace),
        )
        with self.assertRaisesRegex(ValueError, "at least one"):
            contract.applied_action_sequence_sha256([{"step": 1}])
        with self.assertRaises(TypeError):
            contract.applied_action_sequence_sha256([{"applied_action": 1}])

    def test_capture_frame_sequence_is_tied_to_policy_trace(self):
        frame_hashes = ["a" * 64, "b" * 64, "c" * 64]
        trace = [
            {"input_frame_sha256": frame_hashes[0], "applied_action": {"forward": 1}},
            {"input_frame_sha256": frame_hashes[1], "applied_action": {"attack": 1}},
        ]
        evidence = contract.capture_frame_sequence_evidence(
            frame_hashes,
            trace,
            frame_hashes[-1],
            policy_steps=2,
        )
        self.assertTrue(evidence["frame_count_matches_policy_steps"])
        self.assertTrue(evidence["source_frames_match_trace_inputs"])
        self.assertTrue(evidence["final_source_frame_matches_episode"])
        self.assertEqual(evidence["trace_input_frame_sha256s"], frame_hashes[:-1])
        self.assertRegex(evidence["source_frame_sequence_sha256"], r"^[0-9a-f]{64}$")

        reordered = contract.capture_frame_sequence_evidence(
            [frame_hashes[1], frame_hashes[0], frame_hashes[2]],
            trace,
            frame_hashes[-1],
            policy_steps=2,
        )
        self.assertFalse(reordered["source_frames_match_trace_inputs"])

        wrong_final = contract.capture_frame_sequence_evidence(
            frame_hashes,
            trace,
            "d" * 64,
            policy_steps=2,
        )
        self.assertFalse(wrong_final["final_source_frame_matches_episode"])
        with self.assertRaisesRegex(ValueError, "source frame digests"):
            contract.capture_frame_sequence_evidence(["not-a-digest"], [], "c" * 64, 0)

    def test_capture_acceptance_fails_closed_and_is_mandatory_for_gate(self):
        capture = {
            "episode_index": 0,
            "simulator_close_succeeded": True,
            "video": {
                "frame_count": 2,
                "checks": {
                    "codec_is_h264": True,
                    "width_matches": True,
                    "height_matches": True,
                    "fps_matches": True,
                    "frame_count_matches": True,
                },
            },
            "policy_steps": 1,
            "frame_count_matches_policy_steps": True,
            "authoritative_trace_sha256": "a" * 64,
            "applied_action_sha256": "b" * 64,
            "source_frames_match_trace_inputs": True,
            "final_source_frame_matches_episode": True,
            "source_frame_sha256s": ["c" * 64, "d" * 64],
            "source_frame_sequence_sha256": "e" * 64,
        }
        checks = contract.native_capture_acceptance_checks(
            [0],
            [{"episode_index": 0, "valid": True}],
            [capture],
            capture_file_count=1,
            total_capture_bytes=1024,
        )
        self.assertTrue(all(checks.values()))

        failed_checks = contract.native_capture_acceptance_checks(
            [0],
            [{"episode_index": 0, "valid": True}],
            [{**capture, "source_frames_match_trace_inputs": False}],
            capture_file_count=1,
            total_capture_bytes=1024,
        )
        self.assertFalse(failed_checks["all_source_frames_match_trace"])

        suite_outcome = {"evaluated": True, "passed": True}
        accepted = contract.native_gate_acceptance(suite_outcome, True, True)
        self.assertEqual(accepted, {"evaluated": True, "passed": True, "capture_requirement_met": True})
        rejected = contract.native_gate_acceptance(suite_outcome, True, False)
        self.assertEqual(
            rejected,
            {"evaluated": False, "passed": None, "capture_requirement_met": False},
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

    def test_official_attack_stabilizer_matches_released_gui(self):
        raw = {key: 0 for key in contract.ALL_ACTION_KEYS}
        raw.update(
            {
                "attack": 1,
                "back": 1,
                "camera": [1.25, -2.5],
                "forward": 1,
                "jump": 1,
                "left": 1,
                "right": 1,
                "sneak": 1,
                "sprint": 1,
            }
        )
        stabilized, changed = contract.apply_official_attack_stabilizer(raw)
        self.assertEqual(changed, ["jump", "left", "right", "sneak", "sprint"])
        self.assertTrue(all(stabilized[key] == 0 for key in changed))
        self.assertEqual(
            {key: stabilized[key] for key in ("attack", "back", "camera", "forward")},
            {key: raw[key] for key in ("attack", "back", "camera", "forward")},
        )
        self.assertEqual(raw["jump"], 1, "postprocessor must not mutate model evidence")

        inactive = {**raw, "attack": 0}
        unchanged, inactive_changes = contract.apply_official_attack_stabilizer(inactive)
        self.assertEqual(inactive_changes, [])
        self.assertEqual(unchanged, inactive)

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
