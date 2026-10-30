# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Operating contract

@AGENTS.md

The imported file above is binding and takes precedence over default behavior. In
particular: no auto-advance between phases, no preambles, and every response must
end with the Current State Anchor block.

## What this repository is

A **planning and architecture workspace**, not an application. It holds no build
system, no dependencies, and no test suite — there is nothing to build, lint, or
run. All content is Markdown: `context/` is human-authored input, `docs/` is
agent-authored output produced one phase at a time.

The target codebase being designed for lives elsewhere; it is described in
`context/codebase-context.md`. Do not scaffold, install, or generate application
code in this repository.

## Session start checklist

1. Read `STATE.md` — it names the active phase, the last approved artifact, and the
   next step.
2. Read `context/` — the codebase context, tasks, and constraints.
3. Read only the `docs/` artifacts for the current and immediately prior phase.
4. Resume at the state anchor. Do not re-ask questions already answered in
   committed artifacts.

## Writing artifacts

- Write phase artifacts to the paths in the AGENTS.md layout table — never inline a
  full artifact in chat and leave it unwritten.
- An artifact is written **on approval**, not on drafting. Draft in chat, persist
  once approved.
- After persisting, update `STATE.md` in the same turn, then commit. `STATE.md` must
  always match the anchor block of the latest response.

## Git conventions

One branch per phase, named `phase/0N-<slug>` (e.g. `phase/01-prd`). A phase branch
merges to `main` only when its artifact is approved — so `main` holds settled work
and the branch holds whatever is still being argued about. Never start the next
phase's branch off an unmerged one.

Commit one artifact or one approved section per commit, with the phase as the
subject prefix:

```
phase-2: approve TRD section 3 — API contracts
phase-3: add step_01_db_migration.md
state: advance to phase 2.2
```
