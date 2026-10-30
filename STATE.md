# STATE

Durable mirror of the Current State Anchor. **Read this first on every new session.**
Update it in the same turn that an artifact is approved and persisted.

```
==================================================
CURRENT PHASE: Phase 1 [High-Level Scoping & PRD] -> Sub-Phase 1.1
LAST APPROVED: Repository scaffold (AGENTS.md, CLAUDE.md, context/, docs/)
NEXT STEP: BLOCKED on user input. context/ holds placeholders only — no target
           codebase, tasks, or constraints have been supplied. Once filled,
           produce the initial PRD draft into docs/01-prd/PRD.md plus 2-3
           targeted scoping questions.
==================================================
```

**Branch:** `phase/01-prd` (merges to `main` on PRD approval)

## Approval log

Append one row per approved artifact. This is the audit trail that lets a cold
session reconstruct what was settled and what is still open.

| Date | Phase | Artifact | Status |
|---|---|---|---|
| 2026-10-30 | 0 | Repo scaffold + agent operating contract | Approved |
| 2026-10-30 | 1 | `docs/01-prd/PRD.md` | Empty template — not started |

## Open questions

Questions asked but not yet answered. Carry these across sessions; clear them as
they are resolved. Phase 1's 2–3 scoping questions land here once asked.

- _(none yet — the scoping questions cannot be written until `context/` is filled;
  asking them against an unknown stack would produce generic questions, which is
  the failure mode this workflow exists to avoid)_

## Blockers

1. **`context/` is unfilled.** All three files are placeholders:
   - `context/codebase-context.md` — tech stack, directory tree, existing patterns
   - `context/tasks.md` — the interview brief, provided skeleton/stubs, deliverables
   - `context/constraints.md` — format, time budget, hard vs. soft constraints

   Phase 1 cannot legitimately start until these are filled. A PRD written against
   an assumed stack produces user stories that do not survive contact with the real
   repo, and every downstream phase inherits the error.
