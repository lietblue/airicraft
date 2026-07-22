from __future__ import annotations

import argparse
from pathlib import Path
import sys
import unittest

from .workflow import (
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


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        self.exit(2, f"error: {message}\n")


def _parser() -> argparse.ArgumentParser:
    parser = _Parser(prog="agentic-development")
    areas = parser.add_subparsers(dest="area", required=True)

    task = areas.add_parser("task", help="manage a human-directed development task")
    task_commands = task.add_subparsers(dest="command", required=True)
    task_init = task_commands.add_parser(
        "init", help="freeze a human problem or requirement"
    )
    task_init.add_argument("--repo-root", required=True)
    task_init.add_argument("--state-root", required=True)
    task_init.add_argument("--task-id", required=True)
    task_init.add_argument("--type", required=True, choices=("bug", "feature"))
    task_init.add_argument("--title", required=True)
    task_init.add_argument("--problem-file", required=True)
    task_init.add_argument("--base-commit", required=True)
    task_init.add_argument("--max-candidates", type=int, default=4)
    task_status_parser = task_commands.add_parser(
        "status", help="inspect task and candidate state"
    )
    task_status_parser.add_argument("--task-dir", required=True)

    context = areas.add_parser(
        "context", help="record repository grounding and reproduction"
    )
    context_commands = context.add_subparsers(dest="command", required=True)
    context_record = context_commands.add_parser("record")
    context_record.add_argument("--task-dir", required=True)
    context_record.add_argument("--input", required=True)

    spec = areas.add_parser("spec", help="approve the shared task specification")
    spec_commands = spec.add_subparsers(dest="command", required=True)
    spec_approve = spec_commands.add_parser("approve")
    spec_approve.add_argument("--task-dir", required=True)
    spec_approve.add_argument("--input", required=True)
    spec_approve.add_argument("--accept-unreproduced", action="store_true")

    candidate = areas.add_parser(
        "candidate", help="manage parallel isolated candidates"
    )
    candidate_commands = candidate.add_subparsers(dest="command", required=True)
    candidate_open = candidate_commands.add_parser("open")
    candidate_open.add_argument("--task-dir", required=True)
    candidate_open.add_argument("--candidate-id", required=True)
    candidate_open.add_argument("--plan", required=True)
    candidate_verify = candidate_commands.add_parser("verify")
    candidate_verify.add_argument("--task-dir", required=True)
    candidate_verify.add_argument("--candidate-id", required=True)
    candidate_verify.add_argument("--verification-id", required=True)
    candidate_verify.add_argument("--input", required=True)
    candidate_archive = candidate_commands.add_parser(
        "archive", help="remove a clean candidate worktree while preserving its branch"
    )
    candidate_archive.add_argument("--task-dir", required=True)
    candidate_archive.add_argument("--candidate-id", required=True)
    candidate_archive.add_argument(
        "--disposition",
        required=True,
        choices=("merged", "rejected", "superseded", "paused"),
    )
    candidate_archive.add_argument("--reason", required=True)

    review = areas.add_parser("review", help="record the human merge review")
    review_commands = review.add_subparsers(dest="command", required=True)
    review_record = review_commands.add_parser("record")
    review_record.add_argument("--task-dir", required=True)
    review_record.add_argument("--review-id", required=True)
    review_record.add_argument("--input", required=True)
    areas.add_parser("self-test", help="run the agentic-development host tests")
    return parser


def _problem_text(path_value: str) -> str:
    path = Path(path_value).expanduser()
    try:
        return path.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as error:
        raise WorkflowError(f"cannot read problem file {path}: {error}") from error


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    if arguments.area == "self-test":
        suite = unittest.defaultTestLoader.discover(
            str(Path(__file__).resolve().parent / "tests")
        )
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    try:
        if arguments.area == "task" and arguments.command == "init":
            result = start_task(
                repo_root=arguments.repo_root,
                state_root=arguments.state_root,
                task_id=arguments.task_id,
                task_type=arguments.type,
                title=arguments.title,
                problem=_problem_text(arguments.problem_file),
                base_commit=arguments.base_commit,
                max_candidates=arguments.max_candidates,
            )
        elif arguments.area == "task" and arguments.command == "status":
            result = task_status(task_dir=arguments.task_dir)
        elif arguments.area == "context" and arguments.command == "record":
            result = record_context(
                task_dir=arguments.task_dir, input_path=arguments.input
            )
        elif arguments.area == "spec" and arguments.command == "approve":
            result = approve_spec(
                task_dir=arguments.task_dir,
                input_path=arguments.input,
                accept_unreproduced=arguments.accept_unreproduced,
            )
        elif arguments.area == "candidate" and arguments.command == "open":
            result = open_candidate(
                task_dir=arguments.task_dir,
                candidate_id=arguments.candidate_id,
                input_path=arguments.plan,
            )
        elif arguments.area == "candidate" and arguments.command == "verify":
            result = record_verification(
                task_dir=arguments.task_dir,
                candidate_id=arguments.candidate_id,
                verification_id=arguments.verification_id,
                input_path=arguments.input,
            )
        elif arguments.area == "candidate" and arguments.command == "archive":
            result = archive_candidate(
                task_dir=arguments.task_dir,
                candidate_id=arguments.candidate_id,
                disposition=arguments.disposition,
                reason=arguments.reason,
            )
        elif arguments.area == "review" and arguments.command == "record":
            result = record_review(
                task_dir=arguments.task_dir,
                review_id=arguments.review_id,
                input_path=arguments.input,
            )
        else:
            raise WorkflowError("unsupported command")
    except WorkflowError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(canonical_json({"status": "ok", **result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
