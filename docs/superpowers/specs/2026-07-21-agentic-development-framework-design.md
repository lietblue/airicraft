# Agentic Development Framework

## Goal

Give human-directed coding agents enough repository, runtime, evaluator, and review
structure to develop Airicraft without treating it as a black box. The framework is
not a fully autonomous task-generation system. A human owns the problem,
specification, candidate selection, and merge decision.

## Workflow

1. A human reports a bug or feature requirement.
2. An agent grounds the request in the accepted repository base and records relevant
   code paths, architecture, and a reproduction for bugs.
3. Human and agent agree on requirements, acceptance checks, constraints, and
   non-goals.
4. One or more approved candidate plans run in parallel, isolated Git worktrees from
   the same base. Each candidate implements, tests, and verifies its own approach.
5. A human reviews exact candidate commits and verification records, compares viable
   approaches, and decides what is ready to merge into the base branch.

No framework command merges, pushes, changes the control branch or index, or edits
tracked files in the control worktree. Task records are the one intended write there
and live under its ignored `eval-output/agentic-development` directory.

## Human authority

The following transitions require an explicit human-directed command:

- opening the task;
- accepting an unreproduced bug for specification work;
- approving the specification;
- opening each candidate plan;
- recording the final merge review.

Agents may gather evidence, draft specifications and plans, implement candidates,
run verification, and prepare review material. They may not redefine the approved
requirements or select and merge a winner autonomously.

## Task model

Every task is bound to one exact base commit and immutable problem statement. Its
append-only state is stored at the control worktree's canonical
`eval-output/agentic-development/<task-id>` path. Arbitrary state roots, including
Git metadata, are rejected.

The task progresses through these derived stages:

```text
PROBLEM_REPORTED
      |
      v
CONTEXT_GROUNDED
      |
      v
SPEC_APPROVED
      |
      +-------------------+-------------------+
      v                   v                   v
 CANDIDATE A          CANDIDATE B          CANDIDATE C
 plan/worktree        plan/worktree        plan/worktree
 implement/test       implement/test       implement/test
 verify               verify               verify
      \                   |                   /
       +------------------+------------------+
                          v
                    HUMAN REVIEW
                  /              \
        REVIEW_RECORDED        MERGE_READY
```

For a bug, context must record a successful reproduction before specification
approval. A human can explicitly accept an unreproduced bug, but the waiver and its
limitations become part of the immutable specification record. Features use
`NOT_APPLICABLE` reproduction status.

Reproduction follows the same one-live-client and fresh/disposable-world safety rules
as candidate verification. Referenced evidence must be a regular file in the control
worktree; the framework copies and hashes its exact bytes into task state.

## Specification and candidate separation

The approved specification is shared by every candidate and contains behavioral
requirements, acceptance checks, constraints, and non-goals. It states *what* must
be true.

Each candidate has its own immutable plan containing its approach, tradeoffs, and
exact allowed file paths, not directory prefixes. It states *how* that candidate
intends to satisfy the shared specification. Different candidate plans may touch
different approved paths. Ignored evidence files do not belong in `allowedPaths`;
tracked fixtures and tests do.

Candidate branches use:

```text
codex/agentic/<task-id>/<candidate-id>
```

Candidate worktrees use:

```text
.worktrees/agentic-development/<task-id>/<candidate-id>
```

All candidates start from the task's exact base commit. Opening one candidate does
not require closing another. Git mutations are briefly serialized while a worktree
is created; implementation, build, and non-conflicting tests can proceed in parallel.
Minecraft evaluator runs remain serialized, with one client owning the shared
runtime at a time.

## Verification

Agents run repository-appropriate build, test, evaluator, and smoke commands directly
inside their candidate worktree. A verification record binds the reported checks to:

- the task and approved specification hashes;
- the candidate plan;
- the exact candidate commit and tree;
- the clean candidate worktree;
- declared command results, evidence, and limitations.

Completed checks require one or more evidence files from the candidate worktree.
The framework copies those exact bytes into task state and records their size and
SHA-256, so a bare agent-authored `PASS` cannot make a candidate reviewable. The
human still evaluates whether the captured evidence proves the acceptance check.
Every verification check names the approved acceptance checks it covers, and their
union must cover the whole shared specification.

The framework rejects verification when committed changes escape the candidate's
approved path set. A failed verification does not close the candidate; the agent may
commit another repair and record a new verification.

Repository tests, evaluator runs, logs, and smoke artifacts remain evidence
producers. They do not own task selection or merge authority.

## Merge review

A merge review names one exact candidate verification, any other candidates it was
compared against with their exact verifications, the verdict, rationale, and risks.
Supported verdicts are:

- `MERGE_READY`
- `CHANGES_REQUESTED`
- `REJECTED`

`MERGE_READY` requires every recorded check to pass and the candidate branch to
remain clean at the exact verified commit. It means the candidate is ready for human
merge review; it is not permission for the framework to merge or push.

Reviews become stale when the candidate commit changes. A human can explicitly
archive a clean candidate worktree after review or when pausing an approach. Archive
removes only the worktree, preserves the candidate branch and records its exact
commit, and leaves the task inspectable. Missing worktrees are reported rather than
making the whole task unreadable.

Candidate creation is hidden from status readers behind the task mutation lock and
can safely discard an incomplete base-only worktree on an exact retry. Archive first
publishes an immutable intent, then removes the clean worktree, then publishes its
final record. An interrupted archive reports `ARCHIVE_PENDING` and resumes only with
the original disposition and reason. `MERGED` additionally requires the latest
current `MERGE_READY` review, so cleanup cannot bypass implementation or verification.

A later review of the same candidate supersedes its earlier verdict. A comparison
also becomes stale if any compared candidate moves beyond the exact verified commit.
Review records and evidence bundles publish their checksum first and their canonical
JSON last, so concurrent status readers never observe a half-published record;
stranded pre-publication state can be retried.

## Non-goals

- Fully autonomous task discovery or prioritization.
- Autonomous specification approval or winner selection.
- Automatic merge, push, or pull-request publication.
- Running multiple Minecraft evaluator clients concurrently.
- Treating agent-authored verification summaries as a substitute for test or
  evaluator artifacts.
