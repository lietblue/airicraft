from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from typing import Any, Iterator


class WorkflowError(Exception):
    pass


SCHEMA_VERSION = 1
POLICY_ID = "airicraft-agentic-development-v1"
IDENTIFIER_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
FULL_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
MAX_TEXT_BYTES = 128 * 1024
MAX_EVIDENCE_BYTES = 64 * 1024 * 1024
MAX_CANDIDATES_LIMIT = 16
TASK_TYPES = frozenset({"BUG", "FEATURE"})
REPRODUCTION_STATUSES = frozenset({"REPRODUCED", "NOT_REPRODUCED", "NOT_APPLICABLE"})
CHECK_STATUSES = frozenset({"PASS", "FAIL", "NOT_RUN"})
REVIEW_VERDICTS = frozenset({"MERGE_READY", "CHANGES_REQUESTED", "REJECTED"})
ARCHIVE_DISPOSITIONS = frozenset({"MERGED", "REJECTED", "SUPERSEDED", "PAUSED"})


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _reject_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON number {value}")


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _absolute(value: str | Path) -> Path:
    try:
        return Path(os.path.realpath(Path(value).expanduser()))
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        raise WorkflowError(f"cannot resolve path: {error}") from error


def _safe_identifier(value: Any, description: str) -> str:
    if not isinstance(value, str) or IDENTIFIER_RE.fullmatch(value) is None:
        raise WorkflowError(f"{description} must match {IDENTIFIER_RE.pattern}")
    return value


def _nonblank_text(value: Any, description: str) -> str:
    if not isinstance(value, str) or not value.strip() or value != value.strip():
        raise WorkflowError(f"{description} must be nonblank and trimmed")
    if len(value.encode("utf-8")) > MAX_TEXT_BYTES:
        raise WorkflowError(f"{description} exceeds {MAX_TEXT_BYTES} bytes")
    return value


def _string_list(
    value: Any, description: str, *, require_nonempty: bool = False
) -> list[str]:
    if not isinstance(value, list):
        raise WorkflowError(f"{description} must be a list")
    normalized = [_nonblank_text(item, f"{description} item") for item in value]
    if require_nonempty and not normalized:
        raise WorkflowError(f"{description} must not be empty")
    if len(set(normalized)) != len(normalized):
        raise WorkflowError(f"{description} must not contain duplicates")
    return normalized


def _strict_keys(value: Any, expected: set[str], description: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise WorkflowError(f"{description} keys must be exactly {sorted(expected)}")
    return value


def _relative_path(value: Any) -> str:
    text = _nonblank_text(value, "repository path")
    if "\\" in text or "\x00" in text:
        raise WorkflowError(f"unsafe repository path {text!r}")
    path = PurePosixPath(text)
    if (
        path.is_absolute()
        or str(path) != text
        or not path.parts
        or any(part in {"", ".", ".."} for part in path.parts)
        or any(part.casefold() == ".git" for part in path.parts)
    ):
        raise WorkflowError(f"unsafe repository path {text!r}")
    return text


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _load_input(path_value: str | Path) -> Any:
    path = _absolute(path_value)
    try:
        data = path.read_bytes()
    except OSError as error:
        raise WorkflowError(f"cannot read {path}: {error}") from error
    if len(data) > MAX_TEXT_BYTES:
        raise WorkflowError(f"input file exceeds {MAX_TEXT_BYTES} bytes: {path}")
    try:
        return json.loads(
            data.decode("utf-8"),
            parse_constant=_reject_constant,
        )
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise WorkflowError(f"malformed JSON in {path}: {error}") from error


def _write_exclusive(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError as error:
        raise WorkflowError(
            f"cannot create write-once record {path}: {error}"
        ) from error
    try:
        view = memoryview(data)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise WorkflowError(f"short write for {path}")
            view = view[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _write_record(path: Path, document: dict[str, Any]) -> str:
    try:
        data = (canonical_json(document) + "\n").encode("utf-8")
    except (TypeError, ValueError) as error:
        raise WorkflowError(f"record is not canonical JSON: {error}") from error
    digest = _sha256_bytes(data)
    checksum_path = path.with_suffix(".sha256")
    pending_data = path.with_name(f".{path.name}.pending")
    pending_checksum = checksum_path.with_name(f".{checksum_path.name}.pending")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise WorkflowError(f"cannot overwrite write-once record {path}")
    if checksum_path.exists():
        try:
            checksum_path.unlink()
        except OSError as error:
            raise WorkflowError(
                f"cannot recover incomplete record checksum {checksum_path}: {error}"
            ) from error
    for pending in (pending_data, pending_checksum):
        if pending.exists() or pending.is_symlink():
            try:
                pending.unlink()
            except OSError as error:
                raise WorkflowError(
                    f"cannot recover incomplete record staging file {pending}: {error}"
                ) from error
    try:
        _write_exclusive(pending_data, data)
        _write_exclusive(pending_checksum, (digest + "\n").encode("ascii"))
        os.replace(pending_checksum, checksum_path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
        os.replace(pending_data, path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    except Exception:
        if path.is_file() and checksum_path.is_file():
            try:
                if (
                    _sha256_bytes(path.read_bytes()) == digest
                    and checksum_path.read_text(encoding="ascii").strip() == digest
                ):
                    return digest
            except OSError:
                pass
        for pending in (pending_data, pending_checksum):
            try:
                pending.unlink()
            except OSError:
                pass
        raise
    return digest


def _load_record(path: Path, description: str) -> tuple[dict[str, Any], str]:
    try:
        data = path.read_bytes()
        expected = path.with_suffix(".sha256").read_text(encoding="ascii").strip()
    except OSError as error:
        raise WorkflowError(f"cannot read {description}: {error}") from error
    actual = _sha256_bytes(data)
    if expected != actual:
        raise WorkflowError(f"{description} checksum mismatch")
    try:
        value = json.loads(data.decode("utf-8"), parse_constant=_reject_constant)
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise WorkflowError(f"malformed {description}: {error}") from error
    if not isinstance(value, dict):
        raise WorkflowError(f"{description} must be an object")
    return value, actual


def _git(
    cwd: Path,
    *arguments: str,
    check: bool = True,
    text: bool = True,
) -> subprocess.CompletedProcess:
    environment = dict(os.environ)
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=cwd,
            env=environment,
            capture_output=True,
            text=text,
        )
    except OSError as error:
        raise WorkflowError(f"cannot run Git: {error}") from error
    if check and result.returncode != 0:
        stderr = result.stderr if text else result.stderr.decode("utf-8", "replace")
        raise WorkflowError(f"git {' '.join(arguments)} failed: {stderr.strip()}")
    return result


def _repo_identity(repo_value: str | Path) -> tuple[Path, Path]:
    repo = _absolute(repo_value)
    top = _absolute(
        _git(
            repo, "rev-parse", "--path-format=absolute", "--show-toplevel"
        ).stdout.strip()
    )
    if top != repo:
        raise WorkflowError(f"repo root must be the Git top-level: {repo}")
    common = _absolute(
        _git(
            repo, "rev-parse", "--path-format=absolute", "--git-common-dir"
        ).stdout.strip()
    )
    if common.name != ".git":
        raise WorkflowError("repository must use a normal .git common directory")
    if _git(repo, "replace", "-l").stdout:
        raise WorkflowError("Git replacement refs are not allowed")
    return repo, common


def _resolve_commit(repo: Path, commit: str) -> tuple[str, str]:
    if FULL_COMMIT_RE.fullmatch(commit or "") is None:
        raise WorkflowError("base commit must be a full lowercase 40-hex object ID")
    resolved = _git(
        repo, "rev-parse", "--verify", f"{commit}^{{commit}}"
    ).stdout.strip()
    if resolved != commit:
        raise WorkflowError("base commit did not resolve exactly")
    tree = _git(repo, "rev-parse", "--verify", f"{commit}^{{tree}}").stdout.strip()
    return resolved, tree


def _require_clean(repo: Path, description: str) -> None:
    status = _git(
        repo,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
        text=False,
    ).stdout
    if status:
        raise WorkflowError(f"{description} must be clean")


def _task_state_root(repo: Path, state_root_value: str | Path) -> Path:
    state_root = _absolute(state_root_value)
    expected = repo / "eval-output" / "agentic-development"
    if state_root != expected:
        raise WorkflowError(
            "state root must be the control worktree's canonical "
            f"agentic-development directory: {expected}"
        )
    return state_root


def _recover_incomplete_task_directory(task_dir: Path) -> bool:
    if not task_dir.is_dir() or task_dir.is_symlink():
        return False
    allowed = {
        "task-manifest.sha256",
        ".task-manifest.json.pending",
        ".task-manifest.sha256.pending",
    }
    entries = list(task_dir.iterdir())
    if any(entry.name not in allowed or not entry.is_file() for entry in entries):
        return False
    for entry in entries:
        entry.unlink()
    task_dir.rmdir()
    return True


def start_task(
    *,
    repo_root: str | Path,
    state_root: str | Path,
    task_id: str,
    task_type: str,
    title: str,
    problem: str,
    base_commit: str,
    max_candidates: int = 4,
) -> dict[str, Any]:
    repo, common_git = _repo_identity(repo_root)
    task_id = _safe_identifier(task_id, "task id")
    normalized_type = _nonblank_text(task_type, "task type").upper()
    if normalized_type not in TASK_TYPES:
        raise WorkflowError(f"task type must be one of {sorted(TASK_TYPES)}")
    title = _nonblank_text(title, "task title")
    problem = _nonblank_text(problem, "problem statement")
    if (
        isinstance(max_candidates, bool)
        or not isinstance(max_candidates, int)
        or not 1 <= max_candidates <= MAX_CANDIDATES_LIMIT
    ):
        raise WorkflowError(
            f"max candidates must be between 1 and {MAX_CANDIDATES_LIMIT}"
        )
    base, tree = _resolve_commit(repo, base_commit)
    if _git(repo, "rev-parse", "HEAD").stdout.strip() != base:
        raise WorkflowError("task control worktree HEAD must equal the accepted base")
    _require_clean(repo, "task control worktree")
    root = _task_state_root(repo, state_root)
    task_dir = root / task_id
    if task_dir.exists() or task_dir.is_symlink():
        if not _recover_incomplete_task_directory(task_dir):
            raise WorkflowError(f"task state already exists: {task_dir}")
    try:
        task_dir.mkdir(parents=True)
    except OSError as error:
        raise WorkflowError(f"cannot create task state: {error}") from error
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "agentic_development_task",
        "taskId": task_id,
        "taskType": normalized_type,
        "title": title,
        "problem": problem,
        "openedAt": _utc_now(),
        "base": {"commit": base, "tree": tree},
        "repository": {
            "repoRoot": str(repo),
            "commonGitDir": str(common_git),
        },
        "policy": {
            "id": POLICY_ID,
            "maxCandidates": max_candidates,
        },
    }
    digest = _write_record(task_dir / "task-manifest.json", manifest)
    return {
        "taskDir": str(task_dir),
        "taskManifestPath": str(task_dir / "task-manifest.json"),
        "taskManifestSha256": digest,
        "stage": "PROBLEM_REPORTED",
    }


def _load_task(task_dir_value: str | Path) -> dict[str, Any]:
    task_dir = _absolute(task_dir_value)
    manifest, digest = _load_record(task_dir / "task-manifest.json", "task manifest")
    _strict_keys(
        manifest,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "taskType",
            "title",
            "problem",
            "openedAt",
            "base",
            "repository",
            "policy",
        },
        "task manifest",
    )
    if (
        manifest["schemaVersion"] != SCHEMA_VERSION
        or manifest["kind"] != "agentic_development_task"
    ):
        raise WorkflowError("unsupported task manifest")
    _safe_identifier(manifest["taskId"], "task id")
    if manifest["taskType"] not in TASK_TYPES:
        raise WorkflowError("task manifest has invalid task type")
    _nonblank_text(manifest["title"], "task title")
    _nonblank_text(manifest["problem"], "problem statement")
    base = _strict_keys(manifest["base"], {"commit", "tree"}, "task base")
    repository = _strict_keys(
        manifest["repository"], {"repoRoot", "commonGitDir"}, "task repository"
    )
    policy = _strict_keys(manifest["policy"], {"id", "maxCandidates"}, "task policy")
    if (
        policy["id"] != POLICY_ID
        or isinstance(policy["maxCandidates"], bool)
        or not isinstance(policy["maxCandidates"], int)
        or not 1 <= policy["maxCandidates"] <= MAX_CANDIDATES_LIMIT
    ):
        raise WorkflowError("task policy is invalid")
    repo, common_git = _repo_identity(repository["repoRoot"])
    if str(common_git) != repository["commonGitDir"]:
        raise WorkflowError("task Git common directory changed")
    expected_task_dir = (
        repo / "eval-output" / "agentic-development" / manifest["taskId"]
    )
    if task_dir != expected_task_dir:
        raise WorkflowError("task directory does not match its repository and task id")
    resolved, tree = _resolve_commit(repo, base["commit"])
    if resolved != base["commit"] or tree != base["tree"]:
        raise WorkflowError("task base identity changed")
    return {
        "taskDir": task_dir,
        "manifest": manifest,
        "manifestSha256": digest,
        "repo": repo,
        "commonGit": common_git,
    }


def _validate_context_input(value: Any, task_type: str) -> dict[str, Any]:
    context = _strict_keys(
        value,
        {"summary", "codePaths", "architecture", "reproduction"},
        "context input",
    )
    summary = _nonblank_text(context["summary"], "context summary")
    architecture = _nonblank_text(context["architecture"], "architecture grounding")
    code_paths = sorted(
        {
            _relative_path(path)
            for path in _string_list(
                context["codePaths"], "code paths", require_nonempty=True
            )
        }
    )
    reproduction = _strict_keys(
        context["reproduction"],
        {"status", "steps", "evidence", "limitations"},
        "reproduction",
    )
    status = _nonblank_text(reproduction["status"], "reproduction status").upper()
    if status not in REPRODUCTION_STATUSES:
        raise WorkflowError(
            f"reproduction status must be one of {sorted(REPRODUCTION_STATUSES)}"
        )
    steps = _string_list(reproduction["steps"], "reproduction steps")
    evidence = [
        _relative_path(path)
        for path in _string_list(reproduction["evidence"], "reproduction evidence")
    ]
    limitations = _string_list(reproduction["limitations"], "reproduction limitations")
    if task_type == "BUG":
        if status == "NOT_APPLICABLE":
            raise WorkflowError("bug context cannot use NOT_APPLICABLE reproduction")
        if status == "REPRODUCED" and (not steps or not evidence):
            raise WorkflowError(
                "reproduced bugs require reproduction steps and evidence"
            )
        if status == "NOT_REPRODUCED" and not limitations:
            raise WorkflowError(
                "unreproduced bugs require explicit reproduction limitations"
            )
    elif status != "NOT_APPLICABLE":
        raise WorkflowError("feature context must use NOT_APPLICABLE reproduction")
    elif steps or evidence or limitations:
        raise WorkflowError("feature reproduction fields must be empty")
    return {
        "summary": summary,
        "codePaths": code_paths,
        "architecture": architecture,
        "reproduction": {
            "status": status,
            "steps": steps,
            "evidence": evidence,
            "limitations": limitations,
        },
    }


def _capture_context_evidence(
    task: dict[str, Any], source_paths: list[str]
) -> tuple[list[dict[str, Any]], Path | None]:
    if not source_paths:
        return [], None
    evidence_root = task["taskDir"] / "context.evidence"
    if evidence_root.exists() or evidence_root.is_symlink():
        raise WorkflowError(f"context evidence already exists: {evidence_root}")
    evidence_root.mkdir()
    captured = []
    try:
        for index, source_relative in enumerate(source_paths):
            unresolved = task["repo"] / source_relative
            if unresolved.is_symlink():
                raise WorkflowError(
                    f"reproduction evidence must not be a symlink: {source_relative}"
                )
            source = _absolute(unresolved)
            try:
                source.relative_to(task["repo"])
            except ValueError as error:
                raise WorkflowError(
                    f"reproduction evidence escapes control worktree: {source_relative}"
                ) from error
            try:
                stat = source.stat()
            except OSError as error:
                raise WorkflowError(
                    f"cannot inspect reproduction evidence {source_relative}: {error}"
                ) from error
            if not source.is_file():
                raise WorkflowError(
                    f"reproduction evidence must be a regular file: {source_relative}"
                )
            if stat.st_size > MAX_EVIDENCE_BYTES:
                raise WorkflowError(
                    f"reproduction evidence exceeds {MAX_EVIDENCE_BYTES} bytes: "
                    f"{source_relative}"
                )
            try:
                data = source.read_bytes()
            except OSError as error:
                raise WorkflowError(
                    f"cannot read reproduction evidence {source_relative}: {error}"
                ) from error
            if len(data) != stat.st_size:
                raise WorkflowError(
                    f"reproduction evidence changed while reading: {source_relative}"
                )
            digest = _sha256_bytes(data)
            artifact_relative = (
                Path("context.evidence") / f"{index:02d}-{digest}.artifact"
            )
            _write_exclusive(task["taskDir"] / artifact_relative, data)
            captured.append(
                {
                    "sourcePath": source_relative,
                    "artifactPath": artifact_relative.as_posix(),
                    "sha256": digest,
                    "sizeBytes": len(data),
                }
            )
    except Exception:
        _discard_captured_evidence(evidence_root)
        raise
    return captured, evidence_root


def _validate_context_evidence(
    task: dict[str, Any], value: Any, index: int
) -> dict[str, Any]:
    description = f"context evidence {index}"
    evidence = _strict_keys(
        value,
        {"sourcePath", "artifactPath", "sha256", "sizeBytes"},
        description,
    )
    source_path = _relative_path(evidence["sourcePath"])
    artifact_path = _relative_path(evidence["artifactPath"])
    if not artifact_path.startswith("context.evidence/"):
        raise WorkflowError(f"{description} artifact path is outside context state")
    digest = evidence["sha256"]
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise WorkflowError(f"{description} has an invalid SHA-256")
    size = evidence["sizeBytes"]
    if (
        isinstance(size, bool)
        or not isinstance(size, int)
        or not 0 <= size <= MAX_EVIDENCE_BYTES
    ):
        raise WorkflowError(f"{description} has an invalid size")
    unresolved = task["taskDir"] / artifact_path
    if unresolved.is_symlink():
        raise WorkflowError(f"{description} artifact must not be a symlink")
    artifact = _absolute(unresolved)
    try:
        artifact.relative_to(task["taskDir"])
    except ValueError as error:
        raise WorkflowError(f"{description} artifact escapes task state") from error
    if not artifact.is_file():
        raise WorkflowError(f"{description} artifact is unavailable")
    try:
        data = artifact.read_bytes()
    except OSError as error:
        raise WorkflowError(f"cannot read {description} artifact: {error}") from error
    if len(data) != size or _sha256_bytes(data) != digest:
        raise WorkflowError(f"{description} artifact integrity mismatch")
    return {
        "sourcePath": source_path,
        "artifactPath": artifact_path,
        "sha256": digest,
        "sizeBytes": size,
    }


def record_context(*, task_dir: str | Path, input_path: str | Path) -> dict[str, Any]:
    task = _load_task(task_dir)
    context = _validate_context_input(
        _load_input(input_path), task["manifest"]["taskType"]
    )
    for path in context["codePaths"]:
        result = _git(
            task["repo"],
            "cat-file",
            "-e",
            f"{task['manifest']['base']['commit']}:{path}",
            check=False,
        )
        if result.returncode != 0:
            raise WorkflowError(f"grounded code path does not exist at base: {path}")
    with _task_lock(task["taskDir"]):
        path = task["taskDir"] / "context.json"
        if path.exists():
            raise WorkflowError("context record already exists")
        stranded_evidence = task["taskDir"] / "context.evidence"
        if stranded_evidence.is_dir() and not stranded_evidence.is_symlink():
            _discard_captured_evidence(stranded_evidence)
        captured, evidence_root = _capture_context_evidence(
            task, context["reproduction"]["evidence"]
        )
        document = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "agentic_development_context",
            "taskId": task["manifest"]["taskId"],
            "taskManifestSha256": task["manifestSha256"],
            "recordedAt": _utc_now(),
            "evidenceArtifacts": captured,
            **context,
        }
        try:
            digest = _write_record(path, document)
        except Exception:
            if evidence_root is not None:
                _discard_captured_evidence(evidence_root)
            raise
    return {"contextPath": str(path), "contextSha256": digest}


def _load_context(task: dict[str, Any]) -> tuple[dict[str, Any], str]:
    context, digest = _load_record(task["taskDir"] / "context.json", "context record")
    _strict_keys(
        context,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "taskManifestSha256",
            "recordedAt",
            "evidenceArtifacts",
            "summary",
            "codePaths",
            "architecture",
            "reproduction",
        },
        "context record",
    )
    if (
        context["schemaVersion"] != SCHEMA_VERSION
        or context["kind"] != "agentic_development_context"
        or context["taskId"] != task["manifest"]["taskId"]
        or context["taskManifestSha256"] != task["manifestSha256"]
    ):
        raise WorkflowError("context record binding mismatch")
    _nonblank_text(context["recordedAt"], "context recorded timestamp")
    normalized = _validate_context_input(
        {
            "summary": context["summary"],
            "codePaths": context["codePaths"],
            "architecture": context["architecture"],
            "reproduction": context["reproduction"],
        },
        task["manifest"]["taskType"],
    )
    for key, value in normalized.items():
        if context[key] != value:
            raise WorkflowError("context record is not canonical")
    artifacts_value = context["evidenceArtifacts"]
    if not isinstance(artifacts_value, list):
        raise WorkflowError("context evidence artifacts must be a list")
    artifacts = [
        _validate_context_evidence(task, item, index)
        for index, item in enumerate(artifacts_value)
    ]
    if [item["sourcePath"] for item in artifacts] != context["reproduction"][
        "evidence"
    ]:
        raise WorkflowError("context evidence artifacts do not match reproduction")
    if artifacts != artifacts_value:
        raise WorkflowError("context evidence artifacts are not canonical")
    return context, digest


def _validate_spec_input(value: Any) -> dict[str, list[str]]:
    spec = _strict_keys(
        value,
        {"requirements", "acceptanceChecks", "constraints", "nonGoals"},
        "specification input",
    )
    return {
        "requirements": _string_list(
            spec["requirements"], "requirements", require_nonempty=True
        ),
        "acceptanceChecks": _string_list(
            spec["acceptanceChecks"], "acceptance checks", require_nonempty=True
        ),
        "constraints": _string_list(spec["constraints"], "constraints"),
        "nonGoals": _string_list(spec["nonGoals"], "non-goals"),
    }


def approve_spec(
    *,
    task_dir: str | Path,
    input_path: str | Path,
    accept_unreproduced: bool = False,
) -> dict[str, Any]:
    task = _load_task(task_dir)
    context, context_sha = _load_context(task)
    reproduction_status = context["reproduction"]["status"]
    needs_override = (
        task["manifest"]["taskType"] == "BUG" and reproduction_status != "REPRODUCED"
    )
    if accept_unreproduced and not needs_override:
        raise WorkflowError(
            "--accept-unreproduced is valid only for an unreproduced bug"
        )
    if needs_override:
        if not accept_unreproduced:
            raise WorkflowError(
                "bug specification approval requires a reproduced bug or the "
                "explicit --accept-unreproduced human override"
            )
        if not context["reproduction"]["limitations"]:
            raise WorkflowError("unreproduced bug approval requires limitations")
    specification = _validate_spec_input(_load_input(input_path))
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "agentic_development_specification",
        "taskId": task["manifest"]["taskId"],
        "taskManifestSha256": task["manifestSha256"],
        "contextSha256": context_sha,
        "approvedAt": _utc_now(),
        "humanApproval": {
            "acceptedUnreproducedBug": bool(accept_unreproduced),
        },
        **specification,
    }
    path = task["taskDir"] / "specification.json"
    with _task_lock(task["taskDir"]):
        if path.exists():
            raise WorkflowError("specification record already exists")
        digest = _write_record(path, document)
    return {"specificationPath": str(path), "specificationSha256": digest}


def _load_specification(task: dict[str, Any]) -> tuple[dict[str, Any], str]:
    specification, digest = _load_record(
        task["taskDir"] / "specification.json", "specification record"
    )
    _strict_keys(
        specification,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "taskManifestSha256",
            "contextSha256",
            "approvedAt",
            "humanApproval",
            "requirements",
            "acceptanceChecks",
            "constraints",
            "nonGoals",
        },
        "specification record",
    )
    _context, context_sha = _load_context(task)
    if (
        specification["schemaVersion"] != SCHEMA_VERSION
        or specification["kind"] != "agentic_development_specification"
        or specification["taskId"] != task["manifest"]["taskId"]
        or specification["taskManifestSha256"] != task["manifestSha256"]
        or specification["contextSha256"] != context_sha
    ):
        raise WorkflowError("specification record binding mismatch")
    _nonblank_text(specification["approvedAt"], "specification approval timestamp")
    approval = _strict_keys(
        specification["humanApproval"],
        {"acceptedUnreproducedBug"},
        "specification human approval",
    )
    if not isinstance(approval["acceptedUnreproducedBug"], bool):
        raise WorkflowError("specification human approval is invalid")
    needs_override = (
        task["manifest"]["taskType"] == "BUG"
        and _context["reproduction"]["status"] != "REPRODUCED"
    )
    if approval["acceptedUnreproducedBug"] != needs_override:
        raise WorkflowError("specification human approval does not match context")
    normalized = _validate_spec_input(
        {
            "requirements": specification["requirements"],
            "acceptanceChecks": specification["acceptanceChecks"],
            "constraints": specification["constraints"],
            "nonGoals": specification["nonGoals"],
        }
    )
    for key, value in normalized.items():
        if specification[key] != value:
            raise WorkflowError("specification record is not canonical")
    return specification, digest


def _validate_plan_input(value: Any) -> dict[str, Any]:
    plan = _strict_keys(
        value,
        {"summary", "approach", "tradeoffs", "allowedPaths"},
        "candidate plan",
    )
    allowed_paths = sorted(
        {
            _relative_path(path)
            for path in _string_list(
                plan["allowedPaths"], "candidate allowed paths", require_nonempty=True
            )
        }
    )
    return {
        "summary": _nonblank_text(plan["summary"], "candidate summary"),
        "approach": _nonblank_text(plan["approach"], "candidate approach"),
        "tradeoffs": _string_list(plan["tradeoffs"], "candidate tradeoffs"),
        "allowedPaths": allowed_paths,
    }


@contextmanager
def _task_lock(task_dir: Path) -> Iterator[None]:
    path = task_dir / "task-mutation.lock"
    try:
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    except OSError as error:
        raise WorkflowError(f"cannot open task lock: {error}") from error
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


@contextmanager
def _git_mutation_lock(common_git: Path) -> Iterator[None]:
    path = common_git / "airicraft-agentic-development.lock"
    try:
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    except OSError as error:
        raise WorkflowError(f"cannot open Git mutation lock: {error}") from error
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _candidate_root(task: dict[str, Any], candidate_id: str) -> tuple[Path, str]:
    worktree = (
        task["commonGit"].parent
        / ".worktrees"
        / "agentic-development"
        / task["manifest"]["taskId"]
        / candidate_id
    )
    branch = f"codex/agentic/{task['manifest']['taskId']}/{candidate_id}"
    return worktree, branch


def _remove_new_candidate_worktree(
    task: dict[str, Any], worktree: Path, branch: str
) -> bool:
    if not worktree.is_dir():
        return True
    expected = task["manifest"]["base"]["commit"]
    actual_branch = _git(worktree, "branch", "--show-current", check=False)
    actual_head = _git(worktree, "rev-parse", "HEAD", check=False)
    status = _git(
        worktree,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
        check=False,
        text=False,
    )
    if (
        actual_branch.returncode != 0
        or actual_head.returncode != 0
        or status.returncode != 0
        or actual_branch.stdout.strip() != branch
        or actual_head.stdout.strip() != expected
        or status.stdout
    ):
        return False
    removed = _git(task["repo"], "worktree", "remove", str(worktree), check=False)
    if removed.returncode != 0:
        return False
    ref = f"refs/heads/{branch}"
    resolved = _git(task["repo"], "rev-parse", "--verify", ref, check=False)
    if resolved.returncode == 0 and resolved.stdout.strip() == expected:
        _git(task["repo"], "branch", "-d", branch, check=False)
    return True


def _recover_incomplete_candidate(
    task: dict[str, Any], candidate_dir: Path, worktree: Path, branch: str
) -> bool:
    if not candidate_dir.exists() and not candidate_dir.is_symlink():
        return True
    if not candidate_dir.is_dir() or candidate_dir.is_symlink():
        return False
    if (candidate_dir / "candidate-manifest.json").exists():
        return False
    allowed = {
        "candidate-proposal.json",
        "candidate-proposal.sha256",
        "candidate-manifest.sha256",
        ".candidate-proposal.json.pending",
        ".candidate-proposal.sha256.pending",
        ".candidate-manifest.json.pending",
        ".candidate-manifest.sha256.pending",
    }
    entries = list(candidate_dir.iterdir())
    if any(entry.name not in allowed or not entry.is_file() for entry in entries):
        return False
    if worktree.is_dir():
        if not _remove_new_candidate_worktree(task, worktree, branch):
            return False
    elif worktree.exists() or worktree.is_symlink():
        return False
    ref = f"refs/heads/{branch}"
    resolved = _git(task["repo"], "rev-parse", "--verify", ref, check=False)
    if resolved.returncode == 0:
        if resolved.stdout.strip() != task["manifest"]["base"]["commit"]:
            return False
        deleted = _git(task["repo"], "branch", "-d", branch, check=False)
        if deleted.returncode != 0:
            return False
    for entry in entries:
        entry.unlink()
    candidate_dir.rmdir()
    return True


def open_candidate(
    *, task_dir: str | Path, candidate_id: str, input_path: str | Path
) -> dict[str, Any]:
    task = _load_task(task_dir)
    _specification, specification_sha = _load_specification(task)
    candidate_id = _safe_identifier(candidate_id, "candidate id")
    plan = _validate_plan_input(_load_input(input_path))
    candidates_root = task["taskDir"] / "candidates"
    candidate_dir = candidates_root / candidate_id
    worktree, branch = _candidate_root(task, candidate_id)
    with _task_lock(task["taskDir"]):
        if candidate_dir.exists() or candidate_dir.is_symlink():
            if (candidate_dir / "candidate-manifest.json").is_file():
                raise WorkflowError(f"candidate already exists: {candidate_id}")
            with _git_mutation_lock(task["commonGit"]):
                recovered = _recover_incomplete_candidate(
                    task, candidate_dir, worktree, branch
                )
            if not recovered:
                raise WorkflowError(
                    "incomplete candidate state could not be recovered safely: "
                    f"{candidate_id}"
                )
        existing = (
            [path for path in candidates_root.iterdir() if path.is_dir()]
            if candidates_root.is_dir()
            else []
        )
        if (
            candidate_dir not in existing
            and len(existing) >= task["manifest"]["policy"]["maxCandidates"]
        ):
            raise WorkflowError("task candidate budget is exhausted")
        candidate_dir.mkdir(parents=True)
        proposal = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "agentic_development_candidate_proposal",
            "taskId": task["manifest"]["taskId"],
            "candidateId": candidate_id,
            "taskManifestSha256": task["manifestSha256"],
            "specificationSha256": specification_sha,
            "approvedAt": _utc_now(),
            **plan,
        }
        proposal_path = candidate_dir / "candidate-proposal.json"
        proposal_sha = _write_record(proposal_path, proposal)
        created_worktree = False
        try:
            with _git_mutation_lock(task["commonGit"]):
                if worktree.exists() or worktree.is_symlink():
                    raise WorkflowError(
                        f"candidate worktree already exists: {worktree}"
                    )
                ref = f"refs/heads/{branch}"
                if (
                    _git(
                        task["repo"],
                        "show-ref",
                        "--verify",
                        "--quiet",
                        ref,
                        check=False,
                    ).returncode
                    == 0
                ):
                    raise WorkflowError(f"candidate branch already exists: {branch}")
                worktree.parent.mkdir(parents=True, exist_ok=True)
                _git(
                    task["repo"],
                    "worktree",
                    "add",
                    "-b",
                    branch,
                    str(worktree),
                    task["manifest"]["base"]["commit"],
                )
                created_worktree = True
                head = _git(worktree, "rev-parse", "HEAD").stdout.strip()
                tree = _git(worktree, "rev-parse", "HEAD^{tree}").stdout.strip()
                if head != task["manifest"]["base"]["commit"]:
                    raise WorkflowError("candidate worktree opened at the wrong commit")
                manifest = {
                    "schemaVersion": SCHEMA_VERSION,
                    "kind": "agentic_development_candidate",
                    "taskId": task["manifest"]["taskId"],
                    "candidateId": candidate_id,
                    "taskManifestSha256": task["manifestSha256"],
                    "specificationSha256": specification_sha,
                    "proposalSha256": proposal_sha,
                    "openedAt": _utc_now(),
                    "base": dict(task["manifest"]["base"]),
                    "branch": branch,
                    "worktreePath": str(worktree),
                    "initial": {"commit": head, "tree": tree},
                }
                manifest_path = candidate_dir / "candidate-manifest.json"
                manifest_sha = _write_record(manifest_path, manifest)
        except Exception as error:
            cleanup_safe = True
            if created_worktree:
                with _git_mutation_lock(task["commonGit"]):
                    cleanup_safe = _remove_new_candidate_worktree(
                        task, worktree, branch
                    )
            for created in (
                candidate_dir / "candidate-manifest.json",
                candidate_dir / "candidate-manifest.sha256",
                candidate_dir / "candidate-proposal.json",
                candidate_dir / "candidate-proposal.sha256",
            ):
                try:
                    created.unlink()
                except FileNotFoundError:
                    pass
            try:
                candidate_dir.rmdir()
            except OSError:
                pass
            if not cleanup_safe:
                raise WorkflowError(
                    "candidate opening failed and automatic cleanup was refused "
                    f"because {worktree} no longer matched the newly created base"
                ) from error
            raise
    return {
        "candidateId": candidate_id,
        "candidateDir": str(candidate_dir),
        "candidateManifestSha256": manifest_sha,
        "proposalSha256": proposal_sha,
        "branch": branch,
        "worktreePath": str(worktree),
    }


def _load_candidate(task: dict[str, Any], candidate_id: str) -> dict[str, Any]:
    candidate_id = _safe_identifier(candidate_id, "candidate id")
    directory = task["taskDir"] / "candidates" / candidate_id
    manifest, manifest_sha = _load_record(
        directory / "candidate-manifest.json", "candidate manifest"
    )
    proposal, proposal_sha = _load_record(
        directory / "candidate-proposal.json", "candidate proposal"
    )
    _strict_keys(
        manifest,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "candidateId",
            "taskManifestSha256",
            "specificationSha256",
            "proposalSha256",
            "openedAt",
            "base",
            "branch",
            "worktreePath",
            "initial",
        },
        "candidate manifest",
    )
    _strict_keys(
        proposal,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "candidateId",
            "taskManifestSha256",
            "specificationSha256",
            "approvedAt",
            "summary",
            "approach",
            "tradeoffs",
            "allowedPaths",
        },
        "candidate proposal",
    )
    _specification, specification_sha = _load_specification(task)
    if (
        manifest["schemaVersion"] != SCHEMA_VERSION
        or manifest["kind"] != "agentic_development_candidate"
        or manifest["taskId"] != task["manifest"]["taskId"]
        or manifest["candidateId"] != candidate_id
        or manifest["taskManifestSha256"] != task["manifestSha256"]
        or manifest["specificationSha256"] != specification_sha
        or manifest["proposalSha256"] != proposal_sha
        or proposal["schemaVersion"] != SCHEMA_VERSION
        or proposal["kind"] != "agentic_development_candidate_proposal"
        or proposal["taskId"] != task["manifest"]["taskId"]
        or proposal["candidateId"] != candidate_id
        or proposal["taskManifestSha256"] != task["manifestSha256"]
        or proposal["specificationSha256"] != specification_sha
    ):
        raise WorkflowError("candidate binding mismatch")
    normalized_plan = _validate_plan_input(
        {
            "summary": proposal["summary"],
            "approach": proposal["approach"],
            "tradeoffs": proposal["tradeoffs"],
            "allowedPaths": proposal["allowedPaths"],
        }
    )
    for key, value in normalized_plan.items():
        if proposal[key] != value:
            raise WorkflowError("candidate proposal is not canonical")
    _nonblank_text(proposal["approvedAt"], "candidate approval timestamp")
    _nonblank_text(manifest["openedAt"], "candidate opening timestamp")
    base = _strict_keys(manifest["base"], {"commit", "tree"}, "candidate base")
    initial = _strict_keys(
        manifest["initial"], {"commit", "tree"}, "candidate initial state"
    )
    if base != task["manifest"]["base"] or initial != task["manifest"]["base"]:
        raise WorkflowError("candidate base binding mismatch")
    worktree, branch = _candidate_root(task, candidate_id)
    if (
        manifest.get("worktreePath") != str(worktree)
        or manifest.get("branch") != branch
    ):
        raise WorkflowError("candidate worktree identity mismatch")
    return {
        "directory": directory,
        "manifest": manifest,
        "manifestSha256": manifest_sha,
        "proposal": proposal,
        "proposalSha256": proposal_sha,
        "worktree": worktree,
        "branch": branch,
    }


def _candidate_state(
    task: dict[str, Any],
    candidate: dict[str, Any],
    *,
    require_worktree: bool = False,
) -> dict[str, Any]:
    worktree = candidate["worktree"]
    if not worktree.is_dir():
        if require_worktree:
            raise WorkflowError(f"candidate worktree is unavailable: {worktree}")
        ref = f"refs/heads/{candidate['branch']}"
        resolved = _git(task["repo"], "rev-parse", "--verify", ref, check=False)
        if resolved.returncode != 0:
            return {
                "worktreeAvailable": False,
                "branchExists": False,
                "commit": None,
                "tree": None,
                "clean": None,
                "changedPaths": [],
                "outsideAllowedPaths": [],
            }
        head = resolved.stdout.strip()
        clean: bool | None = None
        worktree_available = False
    else:
        branch = _git(worktree, "branch", "--show-current").stdout.strip()
        if branch != candidate["branch"]:
            raise WorkflowError("candidate worktree branch changed")
        head = _git(worktree, "rev-parse", "HEAD").stdout.strip()
        status = _git(
            worktree,
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            text=False,
        ).stdout
        clean = not bool(status)
        worktree_available = True
    if (
        _git(
            task["repo"],
            "merge-base",
            "--is-ancestor",
            task["manifest"]["base"]["commit"],
            head,
            check=False,
        ).returncode
        != 0
    ):
        raise WorkflowError("candidate no longer descends from the task base")
    tree = _git(task["repo"], "rev-parse", f"{head}^{{tree}}").stdout.strip()
    changed_output = _git(
        task["repo"],
        "diff",
        "--name-only",
        task["manifest"]["base"]["commit"],
        head,
    ).stdout
    changed_paths = sorted(path for path in changed_output.splitlines() if path)
    allowed = set(candidate["proposal"]["allowedPaths"])
    outside = sorted(set(changed_paths) - allowed)
    return {
        "worktreeAvailable": worktree_available,
        "branchExists": True,
        "commit": head,
        "tree": tree,
        "clean": clean,
        "changedPaths": changed_paths,
        "outsideAllowedPaths": outside,
    }


def _validate_verification_input(
    value: Any, expected_acceptance_checks: list[str]
) -> dict[str, Any]:
    verification = _strict_keys(
        value, {"summary", "checks", "limitations"}, "verification input"
    )
    checks_value = verification["checks"]
    if not isinstance(checks_value, list) or not checks_value:
        raise WorkflowError("verification checks must be a nonempty list")
    checks = []
    names: set[str] = set()
    covered_acceptance_checks: set[str] = set()
    expected = set(expected_acceptance_checks)
    for index, raw in enumerate(checks_value):
        check = _strict_keys(
            raw,
            {"name", "command", "status", "acceptanceChecks", "evidence"},
            f"verification check {index}",
        )
        name = _nonblank_text(check["name"], f"verification check {index} name")
        if name in names:
            raise WorkflowError("verification check names must be unique")
        names.add(name)
        status = _nonblank_text(
            check["status"], f"verification check {index} status"
        ).upper()
        if status not in CHECK_STATUSES:
            raise WorkflowError(
                f"verification status must be one of {sorted(CHECK_STATUSES)}"
            )
        evidence = [
            _relative_path(path)
            for path in _string_list(
                check["evidence"], f"verification check {index} evidence"
            )
        ]
        if status in {"PASS", "FAIL"} and not evidence:
            raise WorkflowError(
                f"verification check {index} requires durable evidence for {status}"
            )
        acceptance_checks = _string_list(
            check["acceptanceChecks"],
            f"verification check {index} acceptance checks",
        )
        unknown = set(acceptance_checks) - expected
        if unknown:
            raise WorkflowError(
                "verification check references unknown acceptance checks: "
                + ", ".join(sorted(unknown))
            )
        covered_acceptance_checks.update(acceptance_checks)
        checks.append(
            {
                "name": name,
                "command": _nonblank_text(
                    check["command"], f"verification check {index} command"
                ),
                "status": status,
                "acceptanceChecks": acceptance_checks,
                "evidence": evidence,
            }
        )
    missing = expected - covered_acceptance_checks
    if missing:
        raise WorkflowError(
            "verification does not cover approved acceptance checks: "
            + ", ".join(sorted(missing))
        )
    return {
        "summary": _nonblank_text(verification["summary"], "verification summary"),
        "checks": checks,
        "limitations": _string_list(
            verification["limitations"], "verification limitations"
        ),
    }


def _capture_verification_evidence(
    candidate: dict[str, Any],
    verification_id: str,
    checks: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], Path]:
    evidence_root = (
        candidate["directory"] / "verifications" / f"{verification_id}.evidence"
    )
    if evidence_root.exists() or evidence_root.is_symlink():
        raise WorkflowError(f"verification evidence already exists: {evidence_root}")
    evidence_root.mkdir(parents=True)
    captured_checks: list[dict[str, Any]] = []
    try:
        for check_index, check in enumerate(checks):
            captured_evidence = []
            for evidence_index, source_relative in enumerate(check["evidence"]):
                unresolved = candidate["worktree"] / source_relative
                if unresolved.is_symlink():
                    raise WorkflowError(
                        f"verification evidence must not be a symlink: {source_relative}"
                    )
                source = _absolute(unresolved)
                try:
                    source.relative_to(candidate["worktree"])
                except ValueError as error:
                    raise WorkflowError(
                        f"verification evidence escapes candidate worktree: {source_relative}"
                    ) from error
                try:
                    stat = source.stat()
                except OSError as error:
                    raise WorkflowError(
                        f"cannot inspect verification evidence {source_relative}: {error}"
                    ) from error
                if not source.is_file():
                    raise WorkflowError(
                        f"verification evidence must be a regular file: {source_relative}"
                    )
                if stat.st_size > MAX_EVIDENCE_BYTES:
                    raise WorkflowError(
                        f"verification evidence exceeds {MAX_EVIDENCE_BYTES} bytes: "
                        f"{source_relative}"
                    )
                try:
                    data = source.read_bytes()
                except OSError as error:
                    raise WorkflowError(
                        f"cannot read verification evidence {source_relative}: {error}"
                    ) from error
                if len(data) != stat.st_size:
                    raise WorkflowError(
                        f"verification evidence changed while reading: {source_relative}"
                    )
                digest = _sha256_bytes(data)
                artifact_relative = (
                    Path("verifications")
                    / f"{verification_id}.evidence"
                    / f"{check_index:02d}-{evidence_index:02d}-{digest}.artifact"
                )
                artifact_path = candidate["directory"] / artifact_relative
                _write_exclusive(artifact_path, data)
                captured_evidence.append(
                    {
                        "sourcePath": source_relative,
                        "artifactPath": artifact_relative.as_posix(),
                        "sha256": digest,
                        "sizeBytes": len(data),
                    }
                )
            captured_checks.append({**check, "evidence": captured_evidence})
    except Exception:
        for child in evidence_root.iterdir():
            if child.is_file() and not child.is_symlink():
                child.unlink()
        evidence_root.rmdir()
        raise
    return captured_checks, evidence_root


def _discard_captured_evidence(evidence_root: Path) -> None:
    if not evidence_root.is_dir() or evidence_root.is_symlink():
        return
    for child in evidence_root.iterdir():
        if child.is_file() and not child.is_symlink():
            child.unlink()
    evidence_root.rmdir()


def record_verification(
    *,
    task_dir: str | Path,
    candidate_id: str,
    verification_id: str,
    input_path: str | Path,
) -> dict[str, Any]:
    task = _load_task(task_dir)
    candidate = _load_candidate(task, candidate_id)
    if (
        _load_candidate_archive_intent(task, candidate) is not None
        or _load_candidate_archive(task, candidate) is not None
    ):
        raise WorkflowError("candidate verification is closed by archive state")
    specification, _specification_sha = _load_specification(task)
    verification_id = _safe_identifier(verification_id, "verification id")
    verification = _validate_verification_input(
        _load_input(input_path), specification["acceptanceChecks"]
    )
    with _task_lock(task["taskDir"]):
        if (
            _load_candidate_archive_intent(task, candidate) is not None
            or _load_candidate_archive(task, candidate) is not None
        ):
            raise WorkflowError("candidate verification is closed by archive state")
        state = _candidate_state(task, candidate, require_worktree=True)
        if not state["clean"]:
            raise WorkflowError("candidate worktree must be clean before verification")
        if not state["changedPaths"]:
            raise WorkflowError("candidate has no committed changes to verify")
        if state["outsideAllowedPaths"]:
            raise WorkflowError(
                "candidate changed paths outside its approved plan: "
                + ", ".join(state["outsideAllowedPaths"])
            )
        all_passed = all(check["status"] == "PASS" for check in verification["checks"])
        path = candidate["directory"] / "verifications" / f"{verification_id}.json"
        if path.exists():
            raise WorkflowError(
                f"verification record already exists: {verification_id}"
            )
        stranded_evidence = (
            candidate["directory"] / "verifications" / f"{verification_id}.evidence"
        )
        if stranded_evidence.is_dir() and not stranded_evidence.is_symlink():
            _discard_captured_evidence(stranded_evidence)
        captured_checks, evidence_root = _capture_verification_evidence(
            candidate, verification_id, verification["checks"]
        )
        document = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "agentic_development_verification",
            "taskId": task["manifest"]["taskId"],
            "candidateId": candidate["manifest"]["candidateId"],
            "verificationId": verification_id,
            "taskManifestSha256": task["manifestSha256"],
            "specificationSha256": candidate["manifest"]["specificationSha256"],
            "candidateManifestSha256": candidate["manifestSha256"],
            "proposalSha256": candidate["proposalSha256"],
            "recordedAt": _utc_now(),
            "candidate": {
                "commit": state["commit"],
                "tree": state["tree"],
                "changedPaths": state["changedPaths"],
            },
            "allPassed": all_passed,
            "summary": verification["summary"],
            "checks": captured_checks,
            "limitations": verification["limitations"],
        }
        try:
            digest = _write_record(path, document)
        except Exception:
            _discard_captured_evidence(evidence_root)
            raise
    return {
        "verificationPath": str(path),
        "verificationSha256": digest,
        "allPassed": all_passed,
    }


def _validate_captured_evidence(
    candidate: dict[str, Any],
    verification_id: str,
    value: Any,
    description: str,
) -> dict[str, Any]:
    evidence = _strict_keys(
        value,
        {"sourcePath", "artifactPath", "sha256", "sizeBytes"},
        description,
    )
    source_path = _relative_path(evidence["sourcePath"])
    artifact_path = _relative_path(evidence["artifactPath"])
    expected_prefix = f"verifications/{verification_id}.evidence/"
    if not artifact_path.startswith(expected_prefix):
        raise WorkflowError(f"{description} artifact path is outside its record")
    digest = evidence["sha256"]
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise WorkflowError(f"{description} has an invalid SHA-256")
    size = evidence["sizeBytes"]
    if (
        isinstance(size, bool)
        or not isinstance(size, int)
        or not 0 <= size <= MAX_EVIDENCE_BYTES
    ):
        raise WorkflowError(f"{description} has an invalid size")
    unresolved_artifact = candidate["directory"] / artifact_path
    if unresolved_artifact.is_symlink():
        raise WorkflowError(f"{description} artifact must not be a symlink")
    artifact = _absolute(unresolved_artifact)
    try:
        artifact.relative_to(candidate["directory"])
    except ValueError as error:
        raise WorkflowError(
            f"{description} artifact escapes candidate state"
        ) from error
    if not artifact.is_file():
        raise WorkflowError(f"{description} artifact is unavailable")
    try:
        data = artifact.read_bytes()
    except OSError as error:
        raise WorkflowError(f"cannot read {description} artifact: {error}") from error
    if len(data) != size or _sha256_bytes(data) != digest:
        raise WorkflowError(f"{description} artifact integrity mismatch")
    return {
        "sourcePath": source_path,
        "artifactPath": artifact_path,
        "sha256": digest,
        "sizeBytes": size,
    }


def _load_verification(
    task: dict[str, Any], candidate: dict[str, Any], verification_id: str
) -> tuple[dict[str, Any], str]:
    specification, _specification_sha = _load_specification(task)
    expected_acceptance_checks = set(specification["acceptanceChecks"])
    verification_id = _safe_identifier(verification_id, "verification id")
    verification, digest = _load_record(
        candidate["directory"] / "verifications" / f"{verification_id}.json",
        "verification record",
    )
    _strict_keys(
        verification,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "candidateId",
            "verificationId",
            "taskManifestSha256",
            "specificationSha256",
            "candidateManifestSha256",
            "proposalSha256",
            "recordedAt",
            "candidate",
            "allPassed",
            "summary",
            "checks",
            "limitations",
        },
        "verification record",
    )
    if (
        verification["schemaVersion"] != SCHEMA_VERSION
        or verification["kind"] != "agentic_development_verification"
        or verification["taskId"] != task["manifest"]["taskId"]
        or verification["candidateId"] != candidate["manifest"]["candidateId"]
        or verification["verificationId"] != verification_id
        or verification["taskManifestSha256"] != task["manifestSha256"]
        or verification["specificationSha256"]
        != candidate["manifest"]["specificationSha256"]
        or verification["candidateManifestSha256"] != candidate["manifestSha256"]
        or verification["proposalSha256"] != candidate["proposalSha256"]
    ):
        raise WorkflowError("verification record binding mismatch")
    _nonblank_text(verification["recordedAt"], "verification timestamp")
    summary = _nonblank_text(verification["summary"], "verification summary")
    limitations = _string_list(verification["limitations"], "verification limitations")
    checks_value = verification["checks"]
    if not isinstance(checks_value, list) or not checks_value:
        raise WorkflowError("verification checks must be a nonempty list")
    names: set[str] = set()
    covered_acceptance_checks: set[str] = set()
    normalized_checks = []
    for check_index, raw in enumerate(checks_value):
        check = _strict_keys(
            raw,
            {"name", "command", "status", "acceptanceChecks", "evidence"},
            f"verification check {check_index}",
        )
        name = _nonblank_text(check["name"], f"verification check {check_index} name")
        if name in names:
            raise WorkflowError("verification check names must be unique")
        names.add(name)
        status = _nonblank_text(
            check["status"], f"verification check {check_index} status"
        ).upper()
        if status not in CHECK_STATUSES:
            raise WorkflowError("verification check has an invalid status")
        evidence_value = check["evidence"]
        if not isinstance(evidence_value, list):
            raise WorkflowError("verification evidence must be a list")
        captured = [
            _validate_captured_evidence(
                candidate,
                verification_id,
                item,
                f"verification check {check_index} evidence {evidence_index}",
            )
            for evidence_index, item in enumerate(evidence_value)
        ]
        if status in {"PASS", "FAIL"} and not captured:
            raise WorkflowError("completed verification check has no durable evidence")
        acceptance_checks = _string_list(
            check["acceptanceChecks"],
            f"verification check {check_index} acceptance checks",
        )
        unknown = set(acceptance_checks) - expected_acceptance_checks
        if unknown:
            raise WorkflowError(
                "verification record references unknown acceptance checks"
            )
        covered_acceptance_checks.update(acceptance_checks)
        normalized_checks.append(
            {
                "name": name,
                "command": _nonblank_text(
                    check["command"], f"verification check {check_index} command"
                ),
                "status": status,
                "acceptanceChecks": acceptance_checks,
                "evidence": captured,
            }
        )
    if covered_acceptance_checks != expected_acceptance_checks:
        raise WorkflowError("verification record does not cover the specification")
    all_passed = all(check["status"] == "PASS" for check in normalized_checks)
    if verification["allPassed"] is not all_passed:
        raise WorkflowError("verification aggregate result is inconsistent")
    verified_candidate = _strict_keys(
        verification["candidate"],
        {"commit", "tree", "changedPaths"},
        "verified candidate",
    )
    commit, tree = _resolve_commit(task["repo"], verified_candidate["commit"])
    if tree != verified_candidate["tree"]:
        raise WorkflowError("verified candidate tree binding mismatch")
    if (
        _git(
            task["repo"],
            "merge-base",
            "--is-ancestor",
            task["manifest"]["base"]["commit"],
            commit,
            check=False,
        ).returncode
        != 0
    ):
        raise WorkflowError("verified candidate does not descend from task base")
    changed_paths = sorted(
        path
        for path in _git(
            task["repo"],
            "diff",
            "--name-only",
            task["manifest"]["base"]["commit"],
            commit,
        ).stdout.splitlines()
        if path
    )
    recorded_paths = sorted(
        {
            _relative_path(path)
            for path in _string_list(
                verified_candidate["changedPaths"], "verified candidate changed paths"
            )
        }
    )
    if changed_paths != recorded_paths:
        raise WorkflowError("verified candidate changed-path binding mismatch")
    if set(changed_paths) - set(candidate["proposal"]["allowedPaths"]):
        raise WorkflowError("verified candidate escaped its approved paths")
    if (
        verification["summary"] != summary
        or verification["limitations"] != limitations
        or verification["checks"] != normalized_checks
        or verified_candidate["commit"] != commit
        or verified_candidate["changedPaths"] != changed_paths
    ):
        raise WorkflowError("verification record is not canonical")
    return verification, digest


def _latest_current_merge_ready_review(
    task: dict[str, Any], candidate: dict[str, Any], state: dict[str, Any]
) -> tuple[dict[str, Any], str] | None:
    reviews = []
    for review_id in _record_names(task["taskDir"] / "reviews"):
        review, digest = _load_review(task, review_id)
        if review["candidate"]["id"] == candidate["manifest"]["candidateId"]:
            reviews.append((review, digest))
    if not reviews:
        return None
    review, digest = max(reviews, key=lambda item: item[0]["reviewSequence"])
    if (
        review["verdict"] != "MERGE_READY"
        or (state["worktreeAvailable"] and state["clean"] is not True)
        or not state["branchExists"]
        or state["commit"] != review["candidate"]["commit"]
        or state["tree"] != review["candidate"]["tree"]
    ):
        return None
    for compared in review["comparedCandidates"]:
        compared_candidate = _load_candidate(task, compared["id"])
        compared_state = _candidate_state(task, compared_candidate)
        if not (
            compared_state["branchExists"]
            and compared_state["commit"] == compared["commit"]
            and compared_state["tree"] == compared["tree"]
            and (
                not compared_state["worktreeAvailable"]
                or compared_state["clean"] is True
            )
        ):
            return None
    return review, digest


def _load_candidate_archive_intent(
    task: dict[str, Any], candidate: dict[str, Any]
) -> tuple[dict[str, Any], str] | None:
    path = candidate["directory"] / "candidate-archive-intent.json"
    if not path.is_file():
        return None
    intent, digest = _load_record(path, "candidate archive intent")
    _strict_keys(
        intent,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "candidateId",
            "taskManifestSha256",
            "candidateManifestSha256",
            "requestedAt",
            "candidate",
            "disposition",
            "reason",
            "mergeReview",
        },
        "candidate archive intent",
    )
    snapshot = _strict_keys(
        intent["candidate"], {"branch", "commit", "tree"}, "archive intent candidate"
    )
    if (
        intent["schemaVersion"] != SCHEMA_VERSION
        or intent["kind"] != "agentic_development_candidate_archive_intent"
        or intent["taskId"] != task["manifest"]["taskId"]
        or intent["candidateId"] != candidate["manifest"]["candidateId"]
        or intent["taskManifestSha256"] != task["manifestSha256"]
        or intent["candidateManifestSha256"] != candidate["manifestSha256"]
        or snapshot["branch"] != candidate["branch"]
        or intent["disposition"] not in ARCHIVE_DISPOSITIONS
    ):
        raise WorkflowError("candidate archive intent binding mismatch")
    _nonblank_text(intent["requestedAt"], "candidate archive request timestamp")
    _nonblank_text(intent["reason"], "candidate archive reason")
    commit, tree = _resolve_commit(task["repo"], snapshot["commit"])
    if commit != snapshot["commit"] or tree != snapshot["tree"]:
        raise WorkflowError("candidate archive intent snapshot mismatch")
    merge_review = intent["mergeReview"]
    if intent["disposition"] == "MERGED":
        binding = _strict_keys(
            merge_review,
            {"reviewId", "reviewSha256"},
            "candidate archive merge review",
        )
        review_id = _safe_identifier(binding["reviewId"], "archive merge review id")
        review, review_sha = _load_review(task, review_id)
        if (
            binding["reviewSha256"] != review_sha
            or review["verdict"] != "MERGE_READY"
            or review["candidate"]["id"] != candidate["manifest"]["candidateId"]
            or review["candidate"]["commit"] != snapshot["commit"]
            or review["candidate"]["tree"] != snapshot["tree"]
        ):
            raise WorkflowError("candidate archive merge review binding mismatch")
    elif merge_review is not None:
        raise WorkflowError("non-merged archive intent cannot bind a merge review")
    return intent, digest


def archive_candidate(
    *,
    task_dir: str | Path,
    candidate_id: str,
    disposition: str,
    reason: str,
) -> dict[str, Any]:
    task = _load_task(task_dir)
    candidate = _load_candidate(task, candidate_id)
    disposition = _nonblank_text(disposition, "archive disposition").upper()
    if disposition not in ARCHIVE_DISPOSITIONS:
        raise WorkflowError(
            f"archive disposition must be one of {sorted(ARCHIVE_DISPOSITIONS)}"
        )
    reason = _nonblank_text(reason, "archive reason")
    intent_path = candidate["directory"] / "candidate-archive-intent.json"
    path = candidate["directory"] / "candidate-archive.json"
    with _task_lock(task["taskDir"]), _git_mutation_lock(task["commonGit"]):
        if path.exists():
            raise WorkflowError(f"candidate is already archived: {candidate_id}")
        intent_result = _load_candidate_archive_intent(task, candidate)
        if intent_result is None:
            state = _candidate_state(task, candidate, require_worktree=True)
            if not state["clean"]:
                raise WorkflowError("candidate worktree must be clean before archive")
            merge_review_binding = None
            if disposition == "MERGED":
                merge_review_result = _latest_current_merge_ready_review(
                    task, candidate, state
                )
                if merge_review_result is None:
                    raise WorkflowError(
                        "MERGED archive requires the latest current MERGE_READY review"
                    )
                merge_review, merge_review_sha = merge_review_result
                merge_review_binding = {
                    "reviewId": merge_review["reviewId"],
                    "reviewSha256": merge_review_sha,
                }
            intent = {
                "schemaVersion": SCHEMA_VERSION,
                "kind": "agentic_development_candidate_archive_intent",
                "taskId": task["manifest"]["taskId"],
                "candidateId": candidate_id,
                "taskManifestSha256": task["manifestSha256"],
                "candidateManifestSha256": candidate["manifestSha256"],
                "requestedAt": _utc_now(),
                "candidate": {
                    "branch": candidate["branch"],
                    "commit": state["commit"],
                    "tree": state["tree"],
                },
                "disposition": disposition,
                "reason": reason,
                "mergeReview": merge_review_binding,
            }
            intent_sha = _write_record(intent_path, intent)
        else:
            intent, intent_sha = intent_result
            if intent["disposition"] != disposition or intent["reason"] != reason:
                raise WorkflowError(
                    "archive retry must use the original disposition and reason"
                )
        snapshot = intent["candidate"]
        state = _candidate_state(task, candidate)
        if (
            not state["branchExists"]
            or state["commit"] != snapshot["commit"]
            or state["tree"] != snapshot["tree"]
            or (state["worktreeAvailable"] and state["clean"] is not True)
        ):
            raise WorkflowError("candidate changed after archive intent publication")
        if disposition == "MERGED":
            current_review_result = _latest_current_merge_ready_review(
                task, candidate, state
            )
            binding = intent["mergeReview"]
            if current_review_result is None or not isinstance(binding, dict):
                raise WorkflowError(
                    "MERGED archive lost its current MERGE_READY review"
                )
            current_review, current_review_sha = current_review_result
            if (
                binding["reviewId"] != current_review["reviewId"]
                or binding["reviewSha256"] != current_review_sha
            ):
                raise WorkflowError(
                    "MERGED archive review was superseded before finalization"
                )
        if state["worktreeAvailable"]:
            removed = _git(
                task["repo"],
                "worktree",
                "remove",
                str(candidate["worktree"]),
                check=False,
            )
            if removed.returncode != 0:
                raise WorkflowError(
                    f"cannot archive candidate worktree: {removed.stderr.strip()}"
                )
        branch_commit = _git(
            task["repo"],
            "rev-parse",
            "--verify",
            f"refs/heads/{candidate['branch']}",
            check=False,
        )
        if (
            branch_commit.returncode != 0
            or branch_commit.stdout.strip() != snapshot["commit"]
        ):
            raise WorkflowError(
                "candidate branch changed while its worktree was being archived"
            )
        document = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "agentic_development_candidate_archive",
            "taskId": task["manifest"]["taskId"],
            "candidateId": candidate_id,
            "taskManifestSha256": task["manifestSha256"],
            "candidateManifestSha256": candidate["manifestSha256"],
            "archiveIntentSha256": intent_sha,
            "archivedAt": _utc_now(),
            "candidate": dict(snapshot),
            "disposition": disposition,
            "reason": reason,
            "mergeReview": intent["mergeReview"],
            "branchPreserved": True,
        }
        digest = _write_record(path, document)
    return {
        "archivePath": str(path),
        "archiveSha256": digest,
        "candidateId": candidate_id,
        "disposition": disposition,
        "branch": candidate["branch"],
        "branchPreserved": True,
    }


def _load_candidate_archive(
    task: dict[str, Any], candidate: dict[str, Any]
) -> tuple[dict[str, Any], str] | None:
    path = candidate["directory"] / "candidate-archive.json"
    if not path.is_file():
        return None
    archive, digest = _load_record(path, "candidate archive")
    _strict_keys(
        archive,
        {
            "schemaVersion",
            "kind",
            "taskId",
            "candidateId",
            "taskManifestSha256",
            "candidateManifestSha256",
            "archiveIntentSha256",
            "archivedAt",
            "candidate",
            "disposition",
            "reason",
            "mergeReview",
            "branchPreserved",
        },
        "candidate archive",
    )
    intent_result = _load_candidate_archive_intent(task, candidate)
    if intent_result is None:
        raise WorkflowError("candidate archive is missing its intent")
    intent, intent_sha = intent_result
    snapshot = _strict_keys(
        archive["candidate"], {"branch", "commit", "tree"}, "archived candidate"
    )
    if (
        archive["schemaVersion"] != SCHEMA_VERSION
        or archive["kind"] != "agentic_development_candidate_archive"
        or archive["taskId"] != task["manifest"]["taskId"]
        or archive["candidateId"] != candidate["manifest"]["candidateId"]
        or archive["taskManifestSha256"] != task["manifestSha256"]
        or archive["candidateManifestSha256"] != candidate["manifestSha256"]
        or archive["archiveIntentSha256"] != intent_sha
        or snapshot != intent["candidate"]
        or archive["disposition"] != intent["disposition"]
        or archive["reason"] != intent["reason"]
        or archive["mergeReview"] != intent["mergeReview"]
        or archive["branchPreserved"] is not True
    ):
        raise WorkflowError("candidate archive binding mismatch")
    _nonblank_text(archive["archivedAt"], "candidate archive timestamp")
    return archive, digest


def _validate_review_input(value: Any) -> dict[str, Any]:
    review = _strict_keys(
        value,
        {
            "candidateId",
            "verificationId",
            "verdict",
            "summary",
            "risks",
            "comparedCandidates",
        },
        "review input",
    )
    verdict = _nonblank_text(review["verdict"], "review verdict").upper()
    if verdict not in REVIEW_VERDICTS:
        raise WorkflowError(f"review verdict must be one of {sorted(REVIEW_VERDICTS)}")
    compared_value = review["comparedCandidates"]
    if not isinstance(compared_value, list):
        raise WorkflowError("compared candidates must be a list")
    compared_candidates = []
    compared_ids: set[str] = set()
    for index, raw in enumerate(compared_value):
        compared = _strict_keys(
            raw,
            {"candidateId", "verificationId"},
            f"compared candidate {index}",
        )
        candidate_id = _safe_identifier(
            compared["candidateId"], f"compared candidate {index} id"
        )
        if candidate_id in compared_ids:
            raise WorkflowError("compared candidate ids must be unique")
        compared_ids.add(candidate_id)
        compared_candidates.append(
            {
                "candidateId": candidate_id,
                "verificationId": _safe_identifier(
                    compared["verificationId"],
                    f"compared candidate {index} verification id",
                ),
            }
        )
    return {
        "candidateId": _safe_identifier(review["candidateId"], "candidate id"),
        "verificationId": _safe_identifier(review["verificationId"], "verification id"),
        "verdict": verdict,
        "summary": _nonblank_text(review["summary"], "review summary"),
        "risks": _string_list(review["risks"], "review risks"),
        "comparedCandidates": sorted(
            compared_candidates, key=lambda item: item["candidateId"]
        ),
    }


def record_review(
    *, task_dir: str | Path, review_id: str, input_path: str | Path
) -> dict[str, Any]:
    task = _load_task(task_dir)
    review_id = _safe_identifier(review_id, "review id")
    review = _validate_review_input(_load_input(input_path))
    candidate = _load_candidate(task, review["candidateId"])
    if (
        _load_candidate_archive_intent(task, candidate) is not None
        or _load_candidate_archive(task, candidate) is not None
    ):
        raise WorkflowError("candidate review is closed by archive state")
    verification, verification_sha = _load_verification(
        task, candidate, review["verificationId"]
    )
    with _task_lock(task["taskDir"]):
        if (
            _load_candidate_archive_intent(task, candidate) is not None
            or _load_candidate_archive(task, candidate) is not None
        ):
            raise WorkflowError("candidate review is closed by archive state")
        state = _candidate_state(task, candidate, require_worktree=True)
        verified_candidate = verification["candidate"]
        if (
            not state["clean"]
            or state["commit"] != verified_candidate["commit"]
            or state["tree"] != verified_candidate["tree"]
        ):
            raise WorkflowError("candidate changed after the selected verification")
        if review["verdict"] == "MERGE_READY" and verification["allPassed"] is not True:
            raise WorkflowError("MERGE_READY requires an all-passing verification")
        compared_snapshots = []
        for compared in review["comparedCandidates"]:
            if compared["candidateId"] == review["candidateId"]:
                raise WorkflowError(
                    "selected candidate must not compare against itself"
                )
            compared_candidate = _load_candidate(task, compared["candidateId"])
            compared_verification, compared_verification_sha = _load_verification(
                task, compared_candidate, compared["verificationId"]
            )
            compared_state = _candidate_state(
                task, compared_candidate, require_worktree=True
            )
            compared_verified = compared_verification["candidate"]
            if (
                not compared_state["clean"]
                or compared_state["commit"] != compared_verified["commit"]
                or compared_state["tree"] != compared_verified["tree"]
            ):
                raise WorkflowError(
                    f"compared candidate changed after verification: "
                    f"{compared['candidateId']}"
                )
            compared_snapshots.append(
                {
                    "id": compared["candidateId"],
                    "branch": compared_candidate["branch"],
                    "commit": compared_state["commit"],
                    "tree": compared_state["tree"],
                    "candidateManifestSha256": compared_candidate["manifestSha256"],
                    "verificationId": compared["verificationId"],
                    "verificationSha256": compared_verification_sha,
                }
            )
        existing_reviews = [
            _load_review(task, existing_id)[0]
            for existing_id in _record_names(task["taskDir"] / "reviews")
        ]
        review_sequence = (
            max(
                (existing["reviewSequence"] for existing in existing_reviews),
                default=0,
            )
            + 1
        )
        document = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "agentic_development_merge_review",
            "reviewId": review_id,
            "reviewSequence": review_sequence,
            "taskId": task["manifest"]["taskId"],
            "taskManifestSha256": task["manifestSha256"],
            "specificationSha256": candidate["manifest"]["specificationSha256"],
            "candidateManifestSha256": candidate["manifestSha256"],
            "verificationSha256": verification_sha,
            "reviewedAt": _utc_now(),
            "candidate": {
                "id": review["candidateId"],
                "branch": candidate["branch"],
                "commit": state["commit"],
                "tree": state["tree"],
            },
            "verificationId": review["verificationId"],
            "verdict": review["verdict"],
            "summary": review["summary"],
            "risks": review["risks"],
            "comparedCandidates": compared_snapshots,
            "mergeAuthority": "HUMAN_ONLY",
        }
        path = task["taskDir"] / "reviews" / f"{review_id}.json"
        if path.exists():
            raise WorkflowError(f"review record already exists: {review_id}")
        digest = _write_record(path, document)
    return {
        "reviewPath": str(path),
        "reviewSha256": digest,
        "reviewSequence": review_sequence,
        "verdict": review["verdict"],
        "mergeAuthority": "HUMAN_ONLY",
    }


def _load_review(task: dict[str, Any], review_id: str) -> tuple[dict[str, Any], str]:
    review_id = _safe_identifier(review_id, "review id")
    review, digest = _load_record(
        task["taskDir"] / "reviews" / f"{review_id}.json", "merge review"
    )
    _strict_keys(
        review,
        {
            "schemaVersion",
            "kind",
            "reviewId",
            "reviewSequence",
            "taskId",
            "taskManifestSha256",
            "specificationSha256",
            "candidateManifestSha256",
            "verificationSha256",
            "reviewedAt",
            "candidate",
            "verificationId",
            "verdict",
            "summary",
            "risks",
            "comparedCandidates",
            "mergeAuthority",
        },
        "merge review",
    )
    if (
        review["schemaVersion"] != SCHEMA_VERSION
        or review["kind"] != "agentic_development_merge_review"
        or review["reviewId"] != review_id
        or review["taskId"] != task["manifest"]["taskId"]
        or review["taskManifestSha256"] != task["manifestSha256"]
        or review["mergeAuthority"] != "HUMAN_ONLY"
    ):
        raise WorkflowError("merge review binding mismatch")
    review_sequence = review["reviewSequence"]
    if (
        isinstance(review_sequence, bool)
        or not isinstance(review_sequence, int)
        or review_sequence < 1
    ):
        raise WorkflowError("merge review sequence is invalid")
    snapshot = _strict_keys(
        review["candidate"],
        {"id", "branch", "commit", "tree"},
        "reviewed candidate",
    )
    compared_value = review["comparedCandidates"]
    if not isinstance(compared_value, list):
        raise WorkflowError("reviewed comparisons must be a list")
    compared_inputs = []
    normalized_snapshots = []
    for index, raw in enumerate(compared_value):
        compared_snapshot = _strict_keys(
            raw,
            {
                "id",
                "branch",
                "commit",
                "tree",
                "candidateManifestSha256",
                "verificationId",
                "verificationSha256",
            },
            f"reviewed comparison {index}",
        )
        compared_id = _safe_identifier(
            compared_snapshot["id"], f"reviewed comparison {index} id"
        )
        compared_verification_id = _safe_identifier(
            compared_snapshot["verificationId"],
            f"reviewed comparison {index} verification id",
        )
        compared_candidate = _load_candidate(task, compared_id)
        compared_verification, compared_verification_sha = _load_verification(
            task, compared_candidate, compared_verification_id
        )
        compared_verified = compared_verification["candidate"]
        normalized_snapshot = {
            "id": compared_id,
            "branch": compared_candidate["branch"],
            "commit": compared_verified["commit"],
            "tree": compared_verified["tree"],
            "candidateManifestSha256": compared_candidate["manifestSha256"],
            "verificationId": compared_verification_id,
            "verificationSha256": compared_verification_sha,
        }
        if compared_snapshot != normalized_snapshot:
            raise WorkflowError("reviewed comparison evidence binding mismatch")
        compared_inputs.append(
            {
                "candidateId": compared_id,
                "verificationId": compared_verification_id,
            }
        )
        normalized_snapshots.append(normalized_snapshot)
    normalized = _validate_review_input(
        {
            "candidateId": snapshot["id"],
            "verificationId": review["verificationId"],
            "verdict": review["verdict"],
            "summary": review["summary"],
            "risks": review["risks"],
            "comparedCandidates": compared_inputs,
        }
    )
    candidate = _load_candidate(task, normalized["candidateId"])
    verification, verification_sha = _load_verification(
        task, candidate, normalized["verificationId"]
    )
    verified_candidate = verification["candidate"]
    if (
        review["specificationSha256"] != candidate["manifest"]["specificationSha256"]
        or review["candidateManifestSha256"] != candidate["manifestSha256"]
        or review["verificationSha256"] != verification_sha
        or snapshot["branch"] != candidate["branch"]
        or snapshot["commit"] != verified_candidate["commit"]
        or snapshot["tree"] != verified_candidate["tree"]
    ):
        raise WorkflowError("merge review evidence binding mismatch")
    if normalized["verdict"] == "MERGE_READY" and not verification["allPassed"]:
        raise WorkflowError("MERGE_READY review lost its passing verification")
    for compared in normalized["comparedCandidates"]:
        if compared["candidateId"] == normalized["candidateId"]:
            raise WorkflowError("selected candidate must not compare against itself")
    _nonblank_text(review["reviewedAt"], "merge review timestamp")
    if (
        review["verificationId"] != normalized["verificationId"]
        or review["verdict"] != normalized["verdict"]
        or review["summary"] != normalized["summary"]
        or review["risks"] != normalized["risks"]
        or review["comparedCandidates"]
        != sorted(normalized_snapshots, key=lambda item: item["id"])
    ):
        raise WorkflowError("merge review is not canonical")
    return review, digest


def _record_names(directory: Path) -> list[str]:
    if not directory.is_dir():
        return []
    return sorted(path.stem for path in directory.glob("*.json") if path.is_file())


def task_status(*, task_dir: str | Path) -> dict[str, Any]:
    task = _load_task(task_dir)
    with _task_lock(task["taskDir"]):
        return _task_status_loaded(task)


def _task_status_loaded(task: dict[str, Any]) -> dict[str, Any]:
    context_path = task["taskDir"] / "context.json"
    specification_path = task["taskDir"] / "specification.json"
    context_ready = context_path.is_file()
    specification_ready = specification_path.is_file()
    if context_ready:
        _load_context(task)
    if specification_ready:
        _load_specification(task)
    candidates = []
    ready_for_review = []
    candidate_states: dict[str, dict[str, Any]] = {}
    candidate_archives: dict[str, dict[str, Any] | None] = {}
    candidate_archive_intents: dict[str, dict[str, Any] | None] = {}
    candidates_root = task["taskDir"] / "candidates"
    if candidates_root.is_dir():
        for directory in sorted(
            path for path in candidates_root.iterdir() if path.is_dir()
        ):
            candidate = _load_candidate(task, directory.name)
            state = _candidate_state(task, candidate)
            archive_intent_result = _load_candidate_archive_intent(task, candidate)
            archive_intent = (
                archive_intent_result[0] if archive_intent_result is not None else None
            )
            archive_result = _load_candidate_archive(task, candidate)
            archive = archive_result[0] if archive_result is not None else None
            verification_ids = _record_names(candidate["directory"] / "verifications")
            passing = []
            for verification_id in verification_ids:
                verification, _digest = _load_verification(
                    task, candidate, verification_id
                )
                verified_candidate = verification.get("candidate", {})
                if (
                    verification.get("allPassed") is True
                    and verified_candidate.get("commit") == state["commit"]
                    and verified_candidate.get("tree") == state["tree"]
                ):
                    passing.append(verification_id)
            if (
                passing
                and state["worktreeAvailable"]
                and state["clean"]
                and archive is None
                and archive_intent is None
            ):
                ready_for_review.append(directory.name)
            if archive is not None:
                lifecycle = "ARCHIVED"
            elif archive_intent is not None:
                lifecycle = "ARCHIVE_PENDING"
            elif state["worktreeAvailable"]:
                lifecycle = "ACTIVE"
            elif state["branchExists"]:
                lifecycle = "WORKTREE_MISSING"
            else:
                lifecycle = "BRANCH_MISSING"
            candidate_states[directory.name] = state
            candidate_archives[directory.name] = archive
            candidate_archive_intents[directory.name] = archive_intent
            candidates.append(
                {
                    "candidateId": directory.name,
                    "branch": candidate["branch"],
                    "worktreePath": str(candidate["worktree"]),
                    "lifecycle": lifecycle,
                    "worktreeAvailable": state["worktreeAvailable"],
                    "branchExists": state["branchExists"],
                    "commit": state["commit"],
                    "clean": state["clean"],
                    "changedPaths": state["changedPaths"],
                    "outsideAllowedPaths": state["outsideAllowedPaths"],
                    "verificationIds": verification_ids,
                    "passingVerificationIds": passing,
                    "archive": None
                    if archive is None
                    else {
                        "disposition": archive["disposition"],
                        "reason": archive["reason"],
                        "commit": archive["candidate"]["commit"],
                    },
                    "archiveIntent": None
                    if archive_intent is None
                    else {
                        "disposition": archive_intent["disposition"],
                        "reason": archive_intent["reason"],
                        "commit": archive_intent["candidate"]["commit"],
                    },
                }
            )
    review_ids = _record_names(task["taskDir"] / "reviews")
    loaded_reviews = [_load_review(task, review_id)[0] for review_id in review_ids]
    sequences = [review["reviewSequence"] for review in loaded_reviews]
    if sorted(sequences) != list(range(1, len(sequences) + 1)):
        raise WorkflowError("merge review sequences are not contiguous and unique")
    latest_review_by_candidate: dict[str, int] = {}
    for review in loaded_reviews:
        candidate_id = review["candidate"]["id"]
        latest_review_by_candidate[candidate_id] = max(
            latest_review_by_candidate.get(candidate_id, 0),
            review["reviewSequence"],
        )
    reviews = []
    current_merge_ready = []
    for review in sorted(loaded_reviews, key=lambda item: item["reviewSequence"]):
        review_id = review["reviewId"]
        state = candidate_states.get(review["candidate"]["id"])
        selected_archive = candidate_archives.get(review["candidate"]["id"])
        selected_archive_intent = candidate_archive_intents.get(
            review["candidate"]["id"]
        )
        comparisons_current = True
        for compared in review["comparedCandidates"]:
            compared_state = candidate_states.get(compared["id"])
            if not (
                compared_state is not None
                and compared_state["branchExists"]
                and compared_state["commit"] == compared["commit"]
                and compared_state["tree"] == compared["tree"]
                and (
                    not compared_state["worktreeAvailable"]
                    or compared_state["clean"] is True
                )
            ):
                comparisons_current = False
                break
        latest = (
            latest_review_by_candidate.get(review["candidate"]["id"])
            == review["reviewSequence"]
        )
        current = bool(
            latest
            and state is not None
            and state["branchExists"]
            and state["commit"] == review["candidate"]["commit"]
            and state["tree"] == review["candidate"]["tree"]
            and (not state["worktreeAvailable"] or state["clean"] is True)
            and selected_archive is None
            and selected_archive_intent is None
            and comparisons_current
        )
        reviews.append(
            {
                "reviewId": review_id,
                "reviewSequence": review["reviewSequence"],
                "candidateId": review["candidate"]["id"],
                "verificationId": review["verificationId"],
                "verdict": review["verdict"],
                "commit": review["candidate"]["commit"],
                "latestForCandidate": latest,
                "comparisonsCurrent": comparisons_current,
                "current": current,
            }
        )
        if current and review["verdict"] == "MERGE_READY":
            current_merge_ready.append(review_id)
    all_archived = bool(candidates) and all(
        candidate["lifecycle"] == "ARCHIVED" for candidate in candidates
    )
    archive_pending = any(
        candidate["lifecycle"] == "ARCHIVE_PENDING" for candidate in candidates
    )
    archived_dispositions = {
        candidate["archive"]["disposition"]
        for candidate in candidates
        if candidate["archive"] is not None
    }
    if current_merge_ready:
        stage = "MERGE_READY"
    elif all_archived and "MERGED" in archived_dispositions:
        stage = "COMPLETED"
    elif all_archived:
        stage = "ARCHIVED"
    elif archive_pending:
        stage = "ARCHIVING"
    elif reviews:
        stage = "REVIEW_RECORDED"
    elif ready_for_review:
        stage = "READY_FOR_REVIEW"
    elif candidates:
        stage = "IMPLEMENTING"
    elif specification_ready:
        stage = "SPEC_APPROVED"
    elif context_ready:
        stage = "CONTEXT_GROUNDED"
    else:
        stage = "PROBLEM_REPORTED"
    return {
        "taskId": task["manifest"]["taskId"],
        "taskType": task["manifest"]["taskType"],
        "title": task["manifest"]["title"],
        "baseCommit": task["manifest"]["base"]["commit"],
        "stage": stage,
        "contextRecorded": context_ready,
        "specificationApproved": specification_ready,
        "candidates": candidates,
        "readyForReview": sorted(ready_for_review),
        "reviewIds": review_ids,
        "reviews": reviews,
        "currentMergeReadyReviews": sorted(current_merge_ready),
        "mergeAuthority": "HUMAN_ONLY",
    }
