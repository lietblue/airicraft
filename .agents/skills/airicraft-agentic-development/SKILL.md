---
name: airicraft-agentic-development
description: Run human-led Airicraft bug fixes and feature development from a grounded problem through an approved specification, one or more isolated candidate worktrees, exact verification, and human merge review. Use for substantive Airicraft implementation or debugging tasks, especially when competing approaches should be explored in parallel.
---

# Airicraft Agentic Development

Develop Airicraft with repository and runtime evidence instead of treating it as a
black box. The human owns the problem, specification approval, candidate approval,
and merge decision; agents gather evidence and do the bounded implementation work.

Read [the operational guide](../../../docs/agentic-development.md) before starting a
task. Use `scripts/agentic-development` for workflow state.

## Workflow

1. Start only from a clean control worktree at the exact human-accepted base commit.
   Record the human's bug report or feature requirement without broadening it.
2. Trace the relevant runtime corridor and code ownership. For a bug, reproduce it
   and retain concrete logs, evaluator artifacts, or other durable evidence. If it
   cannot be reproduced, state the limitation and wait for an explicit human waiver.
   Use a fresh/disposable world for live reproduction. Run only one live Minecraft
   evaluator client at a time and coordinate that shared-resource boundary explicitly.
3. Draft requirements, acceptance checks, constraints, and non-goals. Discuss them
   with the human. Run `spec approve` only after the human explicitly accepts them.
4. Propose bounded approaches with tradeoffs and exact allowed paths. Run
   `candidate open` only for human-approved candidates.
5. Give each implementation agent exactly one candidate worktree. Implement and
   commit logical changes there, then run the agreed checks and relevant Airicraft
   evaluator or live smoke. Record verification against the exact clean commit.
6. Compare viable candidates against the shared specification. Prepare risks and
   evidence for the human, then record the human verdict. Never merge, push, or
   mutate the base worktree on the framework's authority.

## Parallel candidates

- All candidates share one immutable specification and exact base commit.
- Give candidates distinct IDs, branches, worktrees, plans, and write scopes.
- Treat `allowedPaths` as exact tracked files, not directory prefixes.
- Never let two agents edit the same worktree.
- Builds and isolated tests may run in parallel when they do not contend for shared
  resources. Run only one live Minecraft evaluator client at a time.
- Do not let one candidate silently redefine the shared acceptance checks. If the
  specification changes, open a new task from a newly accepted base.
- Preserve losing candidates until the human finishes comparison and decides on
  cleanup.
- Use `candidate archive` to remove a clean worktree after that decision. It keeps
  the branch and exact archive record; branch deletion remains a separate human Git
  action. Archive only after explicit human cleanup authorization; use `merged` only
  after the merge actually succeeded.

## Evidence and review rules

- Treat declared verification results as an index to real command output and
  artifacts, not a replacement for them.
- Keep planner work at high-level intent; keep low-level execution ownership in the
  action graph/runtime unless the approved specification says otherwise.
- A verification is stale after any new candidate commit or dirty edit.
- Completed checks require real candidate-worktree evidence files; the framework
  preserves and hashes their exact bytes.
- Map every approved acceptance check to at least one verification check. Bind
  comparisons to an exact verification for every alternative.
- Source the repository `.envrc` before builds or tests when it exists.
- `MERGE_READY` means ready for the human's merge procedure. It is not merge
  authority.
- Report the exact candidate branch, commit, checks, evidence, limitations, diff
  scope, and remaining risks in the final review.
