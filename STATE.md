# STATE

Durable mirror of the Current State Anchor. **Read this first on every new session.**
Update it in the same turn that an artifact is approved and persisted.

```
==================================================
CURRENT PHASE: Phase 0 [Bootstrap] -> Sub-Phase 0.1
LAST APPROVED: Repository scaffold (AGENTS.md, CLAUDE.md, context/, docs/)
NEXT STEP: Awaiting user-supplied codebase context, skeleton tasks, and
           constraints in context/ — then begin Phase 1 PRD draft.
==================================================
```

## Approval log

Append one row per approved artifact. This is the audit trail that lets a cold
session reconstruct what was settled and what is still open.

| Date | Phase | Artifact | Status |
|---|---|---|---|
| 2026-10-30 | 0 | Repo scaffold + agent operating contract | Approved |

## Open questions

Questions asked but not yet answered. Carry these across sessions; clear them as
they are resolved.

- _(none — Phase 1 has not started)_

## Blockers

- `context/codebase-context.md`, `context/tasks.md`, and `context/constraints.md`
  are placeholders. Phase 1 cannot start until they are filled in.
