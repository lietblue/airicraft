from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from agentic_development.workflow import (
    WorkflowError,
    approve_spec,
    archive_candidate,
    canonical_json,
    open_candidate,
    record_context,
    record_review,
    record_verification,
    start_task,
    task_status,
)


class AgenticDevelopmentWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name) / "repo"
        self.repo.mkdir()
        self.git("init")
        self.git("config", "user.name", "Agentic Test")
        self.git("config", "user.email", "agentic@example.invalid")
        (self.repo / ".gitignore").write_text(
            ".worktrees/\neval-output/\n", encoding="utf-8"
        )
        (self.repo / "app.txt").write_text("base\n", encoding="utf-8")
        self.git("add", ".gitignore", "app.txt")
        self.git("commit", "-m", "base")
        self.base = self.git("rev-parse", "HEAD").stdout.strip()
        self.state_root = self.repo / "eval-output" / "agentic-development"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(
        self, *arguments: str, cwd: Path | None = None
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", *arguments],
            cwd=cwd or self.repo,
            check=True,
            capture_output=True,
            text=True,
        )

    def write_json(self, name: str, value: object) -> Path:
        path = Path(self.temporary.name) / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def start(self, *, task_type: str = "bug", task_id: str = "task-one") -> Path:
        result = start_task(
            repo_root=self.repo,
            state_root=self.state_root,
            task_id=task_id,
            task_type=task_type,
            title="Grounded task",
            problem="Fix the observed behavior.",
            base_commit=self.base,
            max_candidates=4,
        )
        return Path(result["taskDir"])

    def context_input(self, status: str = "REPRODUCED") -> Path:
        evidence_paths: list[str] = []
        if status == "REPRODUCED":
            evidence = self.repo / "eval-output" / "reproduction.log"
            evidence.parent.mkdir(parents=True, exist_ok=True)
            evidence.write_text("reproduced failure\n", encoding="utf-8")
            evidence_paths.append("eval-output/reproduction.log")
        return self.write_json(
            f"context-{status}.json",
            {
                "summary": "The behavior enters through app.txt.",
                "codePaths": ["app.txt"],
                "architecture": "app.txt is the bounded implementation surface.",
                "reproduction": {
                    "status": status,
                    "steps": ["Run the reproduction"] if status == "REPRODUCED" else [],
                    "evidence": evidence_paths,
                    "limitations": ["Environment unavailable"]
                    if status == "NOT_REPRODUCED"
                    else [],
                },
            },
        )

    def specification_input(self) -> Path:
        return self.write_json(
            "specification.json",
            {
                "requirements": ["The target behavior passes"],
                "acceptanceChecks": ["Focused test passes"],
                "constraints": ["Keep the change bounded"],
                "nonGoals": ["Do not redesign unrelated code"],
            },
        )

    def plan_input(
        self, candidate_id: str, *, allowed: list[str] | None = None
    ) -> Path:
        return self.write_json(
            f"plan-{candidate_id}.json",
            {
                "summary": f"Candidate {candidate_id}",
                "approach": f"Implement approach {candidate_id}.",
                "tradeoffs": ["Small focused change"],
                "allowedPaths": allowed or ["app.txt"],
            },
        )

    def verification_input(self, worktree: Path, *, status: str = "PASS") -> Path:
        evidence = worktree / "eval-output" / f"verification-{status}.log"
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(f"{status} command output\n", encoding="utf-8")
        return self.write_json(
            f"verification-{status}.json",
            {
                "summary": "Focused verification completed.",
                "checks": [
                    {
                        "name": "focused-test",
                        "command": "test app.txt",
                        "status": status,
                        "acceptanceChecks": ["Focused test passes"],
                        "evidence": [f"eval-output/verification-{status}.log"],
                    }
                ],
                "limitations": [],
            },
        )

    def prepare_task(self, *, task_type: str = "bug") -> Path:
        task_dir = self.start(task_type=task_type)
        context = (
            self.context_input("REPRODUCED")
            if task_type == "bug"
            else self.write_json(
                "feature-context.json",
                {
                    "summary": "The feature belongs in app.txt.",
                    "codePaths": ["app.txt"],
                    "architecture": "app.txt owns the feature.",
                    "reproduction": {
                        "status": "NOT_APPLICABLE",
                        "steps": [],
                        "evidence": [],
                        "limitations": [],
                    },
                },
            )
        )
        record_context(task_dir=task_dir, input_path=context)
        approve_spec(task_dir=task_dir, input_path=self.specification_input())
        return task_dir

    def commit_candidate(self, worktree: Path, text: str, *extra_paths: str) -> None:
        (worktree / "app.txt").write_text(text, encoding="utf-8")
        self.git("add", "app.txt", cwd=worktree)
        for extra in extra_paths:
            (worktree / extra).write_text("extra\n", encoding="utf-8")
            self.git("add", extra, cwd=worktree)
        self.git("commit", "-m", "candidate change", cwd=worktree)

    def test_task_requires_clean_exact_base(self) -> None:
        (self.repo / "dirty.txt").write_text("dirty\n", encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "must be clean"):
            self.start()

    def test_bug_spec_requires_reproduction_or_explicit_human_override(self) -> None:
        task_dir = self.start()
        record_context(
            task_dir=task_dir, input_path=self.context_input("NOT_REPRODUCED")
        )
        with self.assertRaisesRegex(WorkflowError, "explicit --accept-unreproduced"):
            approve_spec(task_dir=task_dir, input_path=self.specification_input())
        approved = approve_spec(
            task_dir=task_dir,
            input_path=self.specification_input(),
            accept_unreproduced=True,
        )
        self.assertRegex(approved["specificationSha256"], r"^[0-9a-f]{64}$")

    def test_feature_context_uses_not_applicable_reproduction(self) -> None:
        task_dir = self.prepare_task(task_type="feature")
        self.assertEqual("SPEC_APPROVED", task_status(task_dir=task_dir)["stage"])

    def test_reproduction_evidence_is_copied_and_integrity_checked(self) -> None:
        task_dir = self.start()
        result = record_context(
            task_dir=task_dir, input_path=self.context_input("REPRODUCED")
        )
        context = json.loads(Path(result["contextPath"]).read_text())
        artifact = task_dir / context["evidenceArtifacts"][0]["artifactPath"]
        self.assertEqual("reproduced failure\n", artifact.read_text())
        artifact.write_text("tampered\n", encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "integrity mismatch"):
            task_status(task_dir=task_dir)

    def test_parallel_candidates_share_base_but_keep_distinct_plans(self) -> None:
        task_dir = self.prepare_task()
        first = open_candidate(
            task_dir=task_dir,
            candidate_id="approach-a",
            input_path=self.plan_input("approach-a"),
        )
        second = open_candidate(
            task_dir=task_dir,
            candidate_id="approach-b",
            input_path=self.plan_input("approach-b", allowed=["app.txt", "helper.txt"]),
        )
        self.assertNotEqual(first["branch"], second["branch"])
        self.assertNotEqual(first["worktreePath"], second["worktreePath"])
        self.assertEqual(
            self.base,
            self.git(
                "rev-parse", "HEAD", cwd=Path(first["worktreePath"])
            ).stdout.strip(),
        )
        self.assertEqual(
            self.base,
            self.git(
                "rev-parse", "HEAD", cwd=Path(second["worktreePath"])
            ).stdout.strip(),
        )
        status = task_status(task_dir=task_dir)
        self.assertEqual(
            ["approach-a", "approach-b"],
            [item["candidateId"] for item in status["candidates"]],
        )

    def test_verification_is_bound_to_clean_commit_and_approved_paths(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="bounded",
            input_path=self.plan_input("bounded"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "candidate\n", "outside.txt")
        with self.assertRaisesRegex(WorkflowError, "outside its approved plan"):
            record_verification(
                task_dir=task_dir,
                candidate_id="bounded",
                verification_id="verify-1",
                input_path=self.verification_input(worktree),
            )

    def test_merge_review_never_merges_and_requires_passing_exact_verification(
        self,
    ) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="ready",
            input_path=self.plan_input("ready"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "verified\n")
        verification = record_verification(
            task_dir=task_dir,
            candidate_id="ready",
            verification_id="verify-1",
            input_path=self.verification_input(worktree),
        )
        self.assertTrue(verification["allPassed"])
        review_input = self.write_json(
            "review.json",
            {
                "candidateId": "ready",
                "verificationId": "verify-1",
                "verdict": "MERGE_READY",
                "summary": "Ready for the human merge decision.",
                "risks": ["Review the final diff"],
                "comparedCandidates": [],
            },
        )
        review = record_review(
            task_dir=task_dir,
            review_id="review-1",
            input_path=review_input,
        )
        self.assertEqual("HUMAN_ONLY", review["mergeAuthority"])
        self.assertEqual("MERGE_READY", task_status(task_dir=task_dir)["stage"])
        self.assertEqual(self.base, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("base\n", (self.repo / "app.txt").read_text(encoding="utf-8"))

    def test_merge_ready_rejects_failed_verification(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="failed",
            input_path=self.plan_input("failed"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "failed\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="failed",
            verification_id="verify-failed",
            input_path=self.verification_input(worktree, status="FAIL"),
        )
        review_input = self.write_json(
            "failed-review.json",
            {
                "candidateId": "failed",
                "verificationId": "verify-failed",
                "verdict": "MERGE_READY",
                "summary": "Should not pass.",
                "risks": [],
                "comparedCandidates": [],
            },
        )
        with self.assertRaisesRegex(WorkflowError, "all-passing"):
            record_review(
                task_dir=task_dir,
                review_id="review-failed",
                input_path=review_input,
            )

    def test_new_commit_after_verification_requires_reverification(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="moving",
            input_path=self.plan_input("moving"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "first\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="moving",
            verification_id="verify-first",
            input_path=self.verification_input(worktree),
        )
        self.assertEqual(["moving"], task_status(task_dir=task_dir)["readyForReview"])
        self.commit_candidate(worktree, "second\n")
        self.assertEqual([], task_status(task_dir=task_dir)["readyForReview"])

    def test_state_root_cannot_target_git_metadata(self) -> None:
        with self.assertRaisesRegex(WorkflowError, "canonical"):
            start_task(
                repo_root=self.repo,
                state_root=self.repo / ".git" / "refs" / "heads",
                task_id="unsafe-state",
                task_type="bug",
                title="Unsafe state",
                problem="Do not write workflow state into Git metadata.",
                base_commit=self.base,
            )

    def test_candidates_can_open_concurrently(self) -> None:
        task_dir = self.prepare_task()
        plans = {
            candidate_id: self.plan_input(candidate_id)
            for candidate_id in ("parallel-a", "parallel-b")
        }

        def open_one(candidate_id: str) -> dict[str, object]:
            return open_candidate(
                task_dir=task_dir,
                candidate_id=candidate_id,
                input_path=plans[candidate_id],
            )

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(open_one, plans))
        self.assertEqual(2, len(results))
        self.assertEqual(
            ["parallel-a", "parallel-b"],
            [
                item["candidateId"]
                for item in task_status(task_dir=task_dir)["candidates"]
            ],
        )

    def test_incomplete_candidate_open_is_recovered_on_retry(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="retry-open",
            input_path=self.plan_input("retry-open"),
        )
        candidate_dir = Path(candidate["candidateDir"])
        (candidate_dir / "candidate-manifest.json").unlink()
        (candidate_dir / "candidate-manifest.sha256").unlink()
        retried = open_candidate(
            task_dir=task_dir,
            candidate_id="retry-open",
            input_path=self.plan_input("retry-open"),
        )
        self.assertEqual(
            self.base,
            self.git(
                "rev-parse", "HEAD", cwd=Path(retried["worktreePath"])
            ).stdout.strip(),
        )
        self.assertEqual(
            "ACTIVE", task_status(task_dir=task_dir)["candidates"][0]["lifecycle"]
        )

    def test_passing_verification_requires_and_copies_hashed_evidence(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="evidenced",
            input_path=self.plan_input("evidenced"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "evidenced\n")
        self.verification_input(worktree)
        uncovered = self.write_json(
            "uncovered-acceptance.json",
            {
                "summary": "No acceptance mapping.",
                "checks": [
                    {
                        "name": "uncovered",
                        "command": "true",
                        "status": "PASS",
                        "acceptanceChecks": [],
                        "evidence": ["eval-output/verification-PASS.log"],
                    }
                ],
                "limitations": [],
            },
        )
        with self.assertRaisesRegex(WorkflowError, "does not cover"):
            record_verification(
                task_dir=task_dir,
                candidate_id="evidenced",
                verification_id="uncovered",
                input_path=uncovered,
            )
        empty = self.write_json(
            "empty-evidence.json",
            {
                "summary": "Unsupported assertion.",
                "checks": [
                    {
                        "name": "empty",
                        "command": "true",
                        "status": "PASS",
                        "acceptanceChecks": ["Focused test passes"],
                        "evidence": [],
                    }
                ],
                "limitations": [],
            },
        )
        with self.assertRaisesRegex(WorkflowError, "requires durable evidence"):
            record_verification(
                task_dir=task_dir,
                candidate_id="evidenced",
                verification_id="empty",
                input_path=empty,
            )
        result = record_verification(
            task_dir=task_dir,
            candidate_id="evidenced",
            verification_id="captured",
            input_path=self.verification_input(worktree),
        )
        record = json.loads(Path(result["verificationPath"]).read_text())
        artifact = (
            Path(candidate["candidateDir"])
            / record["checks"][0]["evidence"][0]["artifactPath"]
        )
        self.assertEqual("PASS command output\n", artifact.read_text())
        artifact.write_text("tampered\n", encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "integrity mismatch"):
            task_status(task_dir=task_dir)

    def test_stranded_verification_evidence_is_recovered(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="recovered",
            input_path=self.plan_input("recovered"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "recovered\n")
        stranded = Path(candidate["candidateDir"]) / "verifications" / "retry.evidence"
        stranded.mkdir(parents=True)
        (stranded / "orphan.artifact").write_text("orphan\n", encoding="utf-8")
        result = record_verification(
            task_dir=task_dir,
            candidate_id="recovered",
            verification_id="retry",
            input_path=self.verification_input(worktree),
        )
        self.assertTrue(result["allPassed"])

    def test_incomplete_task_manifest_is_recoverable(self) -> None:
        incomplete = self.state_root / "task-one"
        incomplete.mkdir(parents=True)
        (incomplete / "task-manifest.sha256").write_text(
            "0" * 64 + "\n", encoding="ascii"
        )
        task_dir = self.start()
        self.assertEqual("PROBLEM_REPORTED", task_status(task_dir=task_dir)["stage"])

    def test_rewritten_specification_cannot_break_digest_chain(self) -> None:
        task_dir = self.prepare_task()
        path = task_dir / "specification.json"
        specification = json.loads(path.read_text())
        specification["contextSha256"] = "0" * 64
        data = (canonical_json(specification) + "\n").encode()
        path.write_bytes(data)
        path.with_suffix(".sha256").write_text(
            hashlib.sha256(data).hexdigest() + "\n", encoding="ascii"
        )
        with self.assertRaisesRegex(WorkflowError, "binding mismatch"):
            task_status(task_dir=task_dir)

    def test_review_becomes_stale_after_candidate_changes(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="reviewed",
            input_path=self.plan_input("reviewed"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "reviewed\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="reviewed",
            verification_id="verify-reviewed",
            input_path=self.verification_input(worktree),
        )
        review_input = self.write_json(
            "stale-review.json",
            {
                "candidateId": "reviewed",
                "verificationId": "verify-reviewed",
                "verdict": "MERGE_READY",
                "summary": "Ready at this commit.",
                "risks": [],
                "comparedCandidates": [],
            },
        )
        record_review(
            task_dir=task_dir,
            review_id="stale-review",
            input_path=review_input,
        )
        self.commit_candidate(worktree, "changed-after-review\n")
        status = task_status(task_dir=task_dir)
        self.assertEqual("REVIEW_RECORDED", status["stage"])
        self.assertFalse(status["reviews"][0]["current"])
        self.assertEqual([], status["currentMergeReadyReviews"])

    def test_later_review_supersedes_earlier_merge_ready_verdict(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="superseded-review",
            input_path=self.plan_input("superseded-review"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "superseded-review\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="superseded-review",
            verification_id="verify-review",
            input_path=self.verification_input(worktree),
        )
        for review_id, verdict in (
            ("review-ready", "MERGE_READY"),
            ("review-rejected", "REJECTED"),
        ):
            review_input = self.write_json(
                f"{review_id}.json",
                {
                    "candidateId": "superseded-review",
                    "verificationId": "verify-review",
                    "verdict": verdict,
                    "summary": f"Human verdict: {verdict}.",
                    "risks": [],
                    "comparedCandidates": [],
                },
            )
            record_review(
                task_dir=task_dir,
                review_id=review_id,
                input_path=review_input,
            )
        status = task_status(task_dir=task_dir)
        self.assertEqual("REVIEW_RECORDED", status["stage"])
        self.assertEqual([], status["currentMergeReadyReviews"])
        self.assertFalse(status["reviews"][0]["latestForCandidate"])
        self.assertTrue(status["reviews"][1]["latestForCandidate"])

    def test_review_is_bound_to_exact_compared_candidate_verification(self) -> None:
        task_dir = self.prepare_task()
        selected = open_candidate(
            task_dir=task_dir,
            candidate_id="selected",
            input_path=self.plan_input("selected"),
        )
        alternative = open_candidate(
            task_dir=task_dir,
            candidate_id="alternative",
            input_path=self.plan_input("alternative"),
        )
        selected_worktree = Path(selected["worktreePath"])
        alternative_worktree = Path(alternative["worktreePath"])
        self.commit_candidate(selected_worktree, "selected\n")
        self.commit_candidate(alternative_worktree, "alternative\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="selected",
            verification_id="verify-selected",
            input_path=self.verification_input(selected_worktree),
        )
        record_verification(
            task_dir=task_dir,
            candidate_id="alternative",
            verification_id="verify-alternative",
            input_path=self.verification_input(alternative_worktree),
        )
        review_input = self.write_json(
            "comparison-review.json",
            {
                "candidateId": "selected",
                "verificationId": "verify-selected",
                "verdict": "MERGE_READY",
                "summary": "Selected after exact comparison.",
                "risks": [],
                "comparedCandidates": [
                    {
                        "candidateId": "alternative",
                        "verificationId": "verify-alternative",
                    }
                ],
            },
        )
        record_review(
            task_dir=task_dir,
            review_id="comparison-review",
            input_path=review_input,
        )
        self.assertEqual("MERGE_READY", task_status(task_dir=task_dir)["stage"])
        self.commit_candidate(alternative_worktree, "alternative-changed\n")
        status = task_status(task_dir=task_dir)
        self.assertEqual("REVIEW_RECORDED", status["stage"])
        self.assertFalse(status["reviews"][0]["comparisonsCurrent"])

    def test_archive_removes_only_worktree_and_keeps_task_inspectable(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="paused",
            input_path=self.plan_input("paused"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "paused\n")
        result = archive_candidate(
            task_dir=task_dir,
            candidate_id="paused",
            disposition="paused",
            reason="Human paused this approach after comparison.",
        )
        self.assertFalse(worktree.exists())
        self.assertEqual(
            self.git("rev-parse", result["branch"]).stdout.strip(),
            task_status(task_dir=task_dir)["candidates"][0]["commit"],
        )
        self.assertEqual(
            "ARCHIVED", task_status(task_dir=task_dir)["candidates"][0]["lifecycle"]
        )
        self.assertEqual("ARCHIVED", task_status(task_dir=task_dir)["stage"])

    def test_merged_archive_requires_current_merge_ready_review(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="not-reviewed",
            input_path=self.plan_input("not-reviewed"),
        )
        with self.assertRaisesRegex(WorkflowError, "MERGE_READY"):
            archive_candidate(
                task_dir=task_dir,
                candidate_id="not-reviewed",
                disposition="merged",
                reason="This must not bypass review.",
            )
        self.assertTrue(Path(candidate["worktreePath"]).is_dir())
        self.assertEqual("IMPLEMENTING", task_status(task_dir=task_dir)["stage"])

    def test_reviewed_and_merged_candidate_can_complete(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="merged-candidate",
            input_path=self.plan_input("merged-candidate"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "merged-candidate\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="merged-candidate",
            verification_id="verify-merged",
            input_path=self.verification_input(worktree),
        )
        review_input = self.write_json(
            "merged-review.json",
            {
                "candidateId": "merged-candidate",
                "verificationId": "verify-merged",
                "verdict": "MERGE_READY",
                "summary": "Approved for the normal human merge.",
                "risks": [],
                "comparedCandidates": [],
            },
        )
        record_review(
            task_dir=task_dir,
            review_id="merged-review",
            input_path=review_input,
        )
        self.git("merge", "--ff-only", candidate["branch"])
        archive_candidate(
            task_dir=task_dir,
            candidate_id="merged-candidate",
            disposition="merged",
            reason="Human merge completed successfully.",
        )
        self.assertEqual("COMPLETED", task_status(task_dir=task_dir)["stage"])
        self.assertEqual("merged-candidate\n", (self.repo / "app.txt").read_text())

    def test_archive_intent_blocks_late_review_before_retry(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="locked-archive",
            input_path=self.plan_input("locked-archive"),
        )
        worktree = Path(candidate["worktreePath"])
        self.commit_candidate(worktree, "locked-archive\n")
        record_verification(
            task_dir=task_dir,
            candidate_id="locked-archive",
            verification_id="verify-locked",
            input_path=self.verification_input(worktree),
        )
        ready_input = self.write_json(
            "locked-ready.json",
            {
                "candidateId": "locked-archive",
                "verificationId": "verify-locked",
                "verdict": "MERGE_READY",
                "summary": "Ready before the normal merge.",
                "risks": [],
                "comparedCandidates": [],
            },
        )
        record_review(
            task_dir=task_dir,
            review_id="locked-ready",
            input_path=ready_input,
        )
        self.git("merge", "--ff-only", candidate["branch"])
        self.git("worktree", "lock", candidate["worktreePath"])
        with self.assertRaisesRegex(WorkflowError, "cannot archive"):
            archive_candidate(
                task_dir=task_dir,
                candidate_id="locked-archive",
                disposition="merged",
                reason="Retry after releasing the worktree lock.",
            )
        rejected_input = self.write_json(
            "late-rejection.json",
            {
                "candidateId": "locked-archive",
                "verificationId": "verify-locked",
                "verdict": "REJECTED",
                "summary": "Must not race the published archive intent.",
                "risks": [],
                "comparedCandidates": [],
            },
        )
        with self.assertRaisesRegex(WorkflowError, "closed by archive state"):
            record_review(
                task_dir=task_dir,
                review_id="late-rejection",
                input_path=rejected_input,
            )
        self.git("worktree", "unlock", candidate["worktreePath"])
        archive_candidate(
            task_dir=task_dir,
            candidate_id="locked-archive",
            disposition="merged",
            reason="Retry after releasing the worktree lock.",
        )
        self.assertEqual("COMPLETED", task_status(task_dir=task_dir)["stage"])

    def test_archive_retry_finalizes_published_intent(self) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="archive-retry",
            input_path=self.plan_input("archive-retry"),
        )
        self.commit_candidate(Path(candidate["worktreePath"]), "archive-retry\n")
        archive_candidate(
            task_dir=task_dir,
            candidate_id="archive-retry",
            disposition="paused",
            reason="Pause with recoverable cleanup.",
        )
        candidate_dir = Path(candidate["candidateDir"])
        (candidate_dir / "candidate-archive.json").unlink()
        status = task_status(task_dir=task_dir)
        self.assertEqual("ARCHIVING", status["stage"])
        self.assertEqual("ARCHIVE_PENDING", status["candidates"][0]["lifecycle"])
        archive_candidate(
            task_dir=task_dir,
            candidate_id="archive-retry",
            disposition="paused",
            reason="Pause with recoverable cleanup.",
        )
        self.assertEqual("ARCHIVED", task_status(task_dir=task_dir)["stage"])

    def test_missing_unarchived_worktree_is_reported_without_breaking_status(
        self,
    ) -> None:
        task_dir = self.prepare_task()
        candidate = open_candidate(
            task_dir=task_dir,
            candidate_id="manually-pruned",
            input_path=self.plan_input("manually-pruned"),
        )
        self.git("worktree", "remove", candidate["worktreePath"])
        status = task_status(task_dir=task_dir)
        self.assertEqual("WORKTREE_MISSING", status["candidates"][0]["lifecycle"])


if __name__ == "__main__":
    unittest.main()
