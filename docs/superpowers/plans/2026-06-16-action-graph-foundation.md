# Action Graph Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the first AIRI-241 foundation slice on `dev` without importing stale evaluator, compat, planner, or wrapper churn from `composable-action-graph`.

**Architecture:** Add the standalone action graph domain package, seeded actionset files, and focused unit tests first. This provides primitive metadata, typed facts, in-memory fact storage, actionset parsing/loading/validation, and resolver/debug surfaces that later milestones can wire into planner tools and runtime wrappers.

**Tech Stack:** Java 21, Fabric mod source set, JUnit tests via Gradle, YAML actionset files under `actionsets/`.

---

### Task 1: Import Foundation Files

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/actions/*.java`
- Create: `src/test/java/ai/moeru/airicraft/agent/actions/*.java`
- Create: `actionsets/README.md`
- Create: `actionsets/builtin/make_bread.yml`
- Create: `actionsets/builtin/obtain_wheat.yml`
- Create: `actionsets/enabled/.gitkeep`
- Create: `actionsets/operator/.gitkeep`
- Create: `actionsets/planner_drafts/.gitkeep`

- [ ] **Step 1: Import only the isolated action graph files**

Run:

```bash
git restore --source composable-action-graph -- \
  actionsets \
  src/client/java/ai/moeru/airicraft/agent/actions \
  src/test/java/ai/moeru/airicraft/agent/actions
```

Expected: `git status --short` shows only new files under those paths.

- [ ] **Step 2: Verify no stale integration files were imported**

Run:

```bash
git diff --name-only | rg -v '^(actionsets/|src/client/java/ai/moeru/airicraft/agent/actions/|src/test/java/ai/moeru/airicraft/agent/actions/)'
```

Expected: no output.

### Task 2: Compile And Adapt

**Files:**
- Modify if needed: `src/client/java/ai/moeru/airicraft/agent/actions/*.java`
- Modify if needed: `src/test/java/ai/moeru/airicraft/agent/actions/*.java`

- [ ] **Step 1: Run the focused action tests**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests 'ai.moeru.airicraft.agent.actions.*'
```

Expected: either pass, or fail only because the old branch code needs small adaptation to current `dev` APIs.

- [ ] **Step 2: Fix only compile or assertion drift in the imported foundation**

Allowed changes are limited to:

- Updating imports or constructor calls to match current `dev`.
- Updating tests when current `dev` has equivalent renamed APIs.
- Keeping the architecture direction from `docs/superpowers/specs/2026-06-16-action-graph-architecture-solidification.md`.

Not allowed in this slice:

- Planner tool catalog changes.
- `EmbodiedAgentRuntime` changes.
- Wrapper CLI changes.
- Scenario runner changes.
- Compatibility launcher changes.

- [ ] **Step 3: Re-run focused action tests**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests 'ai.moeru.airicraft.agent.actions.*'
```

Expected: action foundation tests pass.

### Task 3: Foundation Verification

**Files:**
- Verify: `src/client/java/ai/moeru/airicraft/agent/actions/PrimitiveActionRegistry.java`
- Verify: `src/client/java/ai/moeru/airicraft/agent/actions/ActionFactStore.java`
- Verify: `src/client/java/ai/moeru/airicraft/agent/actions/ActionsetValidator.java`
- Verify: `src/client/java/ai/moeru/airicraft/agent/actions/ActionResolver.java`

- [ ] **Step 1: Confirm foundation coverage**

Run:

```bash
rg -n 'class PrimitiveActionRegistry|record ActionFact|class ActionFactStore|class ActionsetValidator|class ActionResolver|class ActionGraphDebugService' src/client/java/ai/moeru/airicraft/agent/actions
```

Expected: each listed foundation type is present.

- [ ] **Step 2: Confirm the seeded actionsets validate through tests**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test \
  --tests ai.moeru.airicraft.agent.actions.ActionsetLibraryLoaderTest \
  --tests ai.moeru.airicraft.agent.actions.ActionsetValidatorTest \
  --tests ai.moeru.airicraft.agent.actions.ActionResolverTest
```

Expected: tests pass and cover seeded actionset loading, validation, and resolver route behavior.

- [ ] **Step 3: Confirm no unrelated worktree drift**

Run:

```bash
git status --short
```

Expected: only the plan, actionsets, action graph source, and action graph tests are modified.

### Task 4: Commit Foundation Slice

**Files:**
- Stage: `docs/superpowers/plans/2026-06-16-action-graph-foundation.md`
- Stage: `actionsets/`
- Stage: `src/client/java/ai/moeru/airicraft/agent/actions/`
- Stage: `src/test/java/ai/moeru/airicraft/agent/actions/`

- [ ] **Step 1: Check staged diff hygiene**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [ ] **Step 2: Stage only foundation files**

Run:

```bash
git add \
  docs/superpowers/plans/2026-06-16-action-graph-foundation.md \
  actionsets \
  src/client/java/ai/moeru/airicraft/agent/actions \
  src/test/java/ai/moeru/airicraft/agent/actions
```

Expected: `git diff --cached --name-only` lists only these paths.

- [ ] **Step 3: Commit**

Run:

```bash
git commit -m "feat(actions): add graph foundation"
```

Expected: commit succeeds with only the foundation slice.
