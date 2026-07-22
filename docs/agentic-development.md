# Airicraft Agentic Development

This is the operational workflow for human-led Airicraft development with Codex or
another coding agent. It supplies repository grounding, isolated candidate
worktrees, and exact verification records. It does not discover work, approve its
own specification, choose a winner, or merge into the base branch.

The immutable task state lives at the control worktree's canonical
`eval-output/agentic-development/<task-id>` path. The directory is ignored by Git
and should be retained until the human has completed review and candidate cleanup.
It is the only intended control-worktree mutation: the framework does not change
tracked files, the branch, or the index there.

## 1. Record the problem

Choose a clean control worktree at the exact base commit the human wants to modify.
Create a text file containing the original problem or requirement, then open a task:

```bash
BASE_COMMIT=$(git rev-parse HEAD)
scripts/agentic-development task init \
  --repo-root "$PWD" \
  --state-root "$PWD/eval-output/agentic-development" \
  --task-id <task-id> \
  --type bug \
  --title "<short title>" \
  --problem-file <problem.txt> \
  --base-commit "$BASE_COMMIT" \
  --max-candidates 4
```

Use `--type feature` for a feature requirement. Task creation fails unless the
control worktree is clean, its `HEAD` exactly matches the supplied full commit, and
the task ID is unused.

## 2. Ground context and reproduce

Trace the request into concrete code and runtime ownership. For a bug, reproduce it
when possible and retain real evidence. Use a fresh or disposable world unless the
human explicitly approves shared-world impact. Reproduction and later live checks
contend for shared Minecraft resources, so run only one live evaluator client at a
time and coordinate ownership explicitly. Record the result with a JSON input:

```json
{
  "summary": "Observed behavior and bounded code corridor.",
  "codePaths": ["src/client/java/example/RelevantRuntime.java"],
  "architecture": "Why this component owns the behavior.",
  "reproduction": {
    "status": "REPRODUCED",
    "steps": ["Exact command or interaction"],
    "evidence": ["eval-output/reproduction/failure.log"],
    "limitations": []
  }
}
```

```bash
scripts/agentic-development context record \
  --task-dir <task-dir> \
  --input <context.json>
```

Bug statuses are `REPRODUCED` and `NOT_REPRODUCED`. An unreproduced bug must list
limitations and cannot proceed without an explicit human waiver. Features use
`NOT_APPLICABLE` with empty reproduction fields.

Evidence entries are repository-relative regular files in the clean control
worktree. Point at concrete logs or artifact manifests rather than a directory. The
framework copies their exact bytes into task state and records size and SHA-256.

## 3. Discuss and approve the specification

Draft the shared behavioral contract after discussing it with the human:

```json
{
  "requirements": ["Behavior that every candidate must provide"],
  "acceptanceChecks": ["Observable check that decides whether it works"],
  "constraints": ["Architecture, compatibility, or scope constraint"],
  "nonGoals": ["Explicitly excluded work"]
}
```

After the human approves it:

```bash
scripts/agentic-development spec approve \
  --task-dir <task-dir> \
  --input <specification.json>
```

For an unreproduced bug, the human must deliberately add
`--accept-unreproduced`. Context and specification records are write-once; if the
problem or shared specification materially changes, open a new task from a newly
accepted base.

## 4. Open isolated candidate approaches

Each proposed solution gets its own human-approved plan:

```json
{
  "summary": "Bounded name for this approach.",
  "approach": "Implementation strategy and ownership boundary.",
  "tradeoffs": ["Known advantage or cost"],
  "allowedPaths": ["src/client/java/example/RelevantRuntime.java"]
}
```

`allowedPaths` entries are exact tracked file paths, not directory prefixes. List
both sides of a rename. Include tracked tests or fixtures the candidate may edit;
ignored logs and evaluator evidence do not need to be listed.

```bash
scripts/agentic-development candidate open \
  --task-dir <task-dir> \
  --candidate-id <candidate-id> \
  --plan <candidate-plan.json>
```

The command creates:

```text
branch:   codex/agentic/<task-id>/<candidate-id>
worktree: .worktrees/agentic-development/<task-id>/<candidate-id>
```

Open any approved candidates independently. They all start from the task's exact
base and share the specification, but keep separate plans, branches, worktrees, and
verification histories. Give an implementation agent only its candidate worktree.
Never let multiple agents edit the same worktree.

Builds and isolated tests may run concurrently. Live Minecraft evaluator runs must
remain serialized, with only one client owning the shared runtime at a time.

Implement and commit logical changes inside each candidate. The framework will not
record a verification for a dirty worktree, a candidate with no committed change,
or a diff that escapes the candidate plan's exact `allowedPaths`.

## 5. Verify exact candidates

Run the approved checks in the candidate worktree. Preserve command output and real
evaluator or smoke artifacts, then index them in a verification input:

```json
{
  "summary": "What was verified on this exact commit.",
  "checks": [
    {
      "name": "focused-tests",
      "command": "./gradlew test --tests ...",
      "status": "PASS",
      "acceptanceChecks": ["Observable check that decides whether it works"],
      "evidence": ["eval-output/verification/focused-tests.log"]
    }
  ],
  "limitations": ["Anything this run did not establish"]
}
```

Check statuses are `PASS`, `FAIL`, and `NOT_RUN`.

Before build or test commands, source the repository `.envrc` when it exists. Every
approved acceptance check must appear in at least one check's `acceptanceChecks`
list; unknown or uncovered acceptance checks are rejected.

```bash
scripts/agentic-development candidate verify \
  --task-dir <task-dir> \
  --candidate-id <candidate-id> \
  --verification-id <verification-id> \
  --input <verification.json>
```

Evidence entries are repository-relative regular files inside the candidate
worktree. A completed `PASS` or `FAIL` check requires at least one. The framework
copies the exact bytes into immutable task state and records each file's size and
SHA-256; a status string by itself cannot make a candidate reviewable.

The verification record binds those artifacts and the result to the clean
candidate's exact commit, tree, changed paths, plan, and shared specification. Any
later edit or commit makes that verification stale. Fix the candidate, commit again,
rerun checks, and write a new verification ID.

Inspect the full task at any point:

```bash
scripts/agentic-development task status --task-dir <task-dir>
```

`readyForReview` includes only clean candidates with an all-passing verification at
their current commit.

## 6. Record the human merge review

Compare candidates against the same specification, including their exact diffs,
checks, artifacts, limitations, and risks. Record the human verdict:

```json
{
  "candidateId": "<selected-candidate>",
  "verificationId": "<passing-verification>",
  "verdict": "MERGE_READY",
  "summary": "Why this exact candidate is or is not suitable.",
  "risks": ["Remaining review concern"],
  "comparedCandidates": [
    {
      "candidateId": "<other-candidate>",
      "verificationId": "<its-exact-verification>"
    }
  ]
}
```

```bash
scripts/agentic-development review record \
  --task-dir <task-dir> \
  --review-id <review-id> \
  --input <review.json>
```

Verdicts are `MERGE_READY`, `CHANGES_REQUESTED`, and `REJECTED`. `MERGE_READY`
requires an all-passing verification and an unchanged, clean candidate at the exact
verified commit. Every review states `mergeAuthority: HUMAN_ONLY`. The command does
not merge, push, switch branches, or modify the base worktree.

The latest review for a candidate supersedes its earlier verdict. A review also
becomes stale if the selected candidate or any compared candidate changes beyond
the exact verified commit used in the decision.

After an explicit human cleanup decision, archive a clean candidate worktree while
retaining its branch and immutable evidence. This removes the worktree directory,
so do not run it merely because verification finished:

```bash
scripts/agentic-development candidate archive \
  --task-dir <task-dir> \
  --candidate-id <candidate-id> \
  --disposition paused \
  --reason "Paused after human comparison"
```

Dispositions are `merged`, `rejected`, `superseded`, and `paused`. Archive never
deletes the branch. Use `merged` only after the normal human-authorized merge has
actually succeeded; the command also requires the latest review to remain
`MERGE_READY` at the exact verified commit. Delete redundant branches separately
with Git only after the human has decided they are no longer needed.

Archive publishes its intent before removing the worktree. If interrupted, task
status reports `ARCHIVE_PENDING` / `ARCHIVING`; rerun the exact command with the
original disposition and reason to finalize it. If a worktree is removed outside
this command, task status reports `WORKTREE_MISSING` instead of making the task
uninspectable. Candidate creation has the same retry principle: a base-only,
incomplete candidate can be reopened safely with its original ID and plan.
