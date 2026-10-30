# STATE

Durable mirror of the Current State Anchor. **Read this first on every new session.**
Update it in the same turn that an artifact is approved and persisted.

```
==================================================
CURRENT PHASE: Phase 2 [Technical Requirement Document] -> Sub-Phase 2.1
LAST APPROVED: docs/01-prd/PRD.md — merged to main (f54450b)
NEXT STEP: Review docs/02-trd/TRD.md skeleton + proposed expansion order.
           On approval -> Phase 2.2, expanding ONE section at a time,
           starting with §1.4 (concurrency and atomicity).
==================================================
```

**Branch:** `phase/02-trd` — merges to `main` on TRD approval
**Project:** Glofox Memberships Purchase API take-home (Java 21 / Spring Boot 4.0.0)

## Approval log

| Date | Phase | Artifact | Status |
|---|---|---|---|
| 2026-10-30 | 0 | Repo scaffold + agent operating contract | Approved |
| 2026-10-30 | 0 | `55036b4` skeleton vendor import (unmodified) | Done |
| 2026-10-30 | 0 | `9f52e7a` gradle wrapper path fix | Done, verified |
| 2026-10-30 | 1 | `context/` filled from verified skeleton | Done |
| 2026-10-30 | 1 | `docs/01-prd/PRD.md` | **Approved** — merged `f54450b` |
| 2026-10-30 | 2 | `docs/02-trd/TRD.md` skeleton | Draft — awaiting review |

## Locked decisions

Full reasoning in PRD §9. Summarised here so a cold session does not re-litigate.

| # | Decision | Choice |
|---|---|---|
| D-1 | JSON naming | `snake_case` |
| D-2 | "Active unlimited" | Status-only, dates ignored (keeps domain clock-free) |
| D-3 | Error shape | RFC 9457 `ProblemDetail` + `code` extension |
| D-4 | Member identity | Trim + case-fold match key; store as submitted |
| D-5 | Name uniqueness beyond unlimited | None |
| D-6 | Payment failure status | `402` / `payment_declined` |
| D-7 | Plan reference on purchase | Snapshot, not bare `plan_id` |

**Scope:** Tier 0 core + Tier 1 error codes committed. Tier 2 (cancel) and
Tier 3 (idempotency) gated — PRD §10.

## Open questions

Carried across sessions. Clear as resolved.

- **Q-1** Reject `start_date` in the past? Currently accepted (AS-5). No rule in
  the brief; needs a product answer.
- **Q-2** Does cancelling an unlimited membership immediately free the member to
  buy another? Only matters if Tier 2 is reached. Proposed: yes.
- **Q-3** Split payment decline (`402`) from gateway outage (`502`)? Blocked —
  the port cannot distinguish them today.

## Verified build health

```
./gradlew --version   -> Gradle 9.5.1 on JVM 22.0.1
./gradlew test        -> BUILD SUCCESSFUL (contextLoads)
```

## Blockers

None. `context/` is filled; the build runs.

## Carried risks for Phase 2

Not blockers, but the TRD must address each. Detail in
`context/codebase-context.md`.

1. `@MockBean` is **removed in Boot 4** — the failing-gateway test needs
   `@MockitoBean` or `@TestConfiguration` + `@Primary`.
2. No `spring-boot-starter-validation` on the classpath — add it or hand-roll.
3. `remainingSlots == null` means *unlimited*, not zero (3 of 4 plans).
4. `PaymentFailedException` is unchecked — easy to leak a `500` over partial state.
5. NFR-2 ordering (charge vs decrement) is the TRD's central decision.
