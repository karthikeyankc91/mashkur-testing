# STATE

Durable mirror of the Current State Anchor. **Read this first on every new session.**
Update it in the same turn that an artifact is approved and persisted.

```
==================================================
CURRENT PHASE: Phase 2 [Technical Requirement Document] -> Sub-Phase 2.2
LAST APPROVED: TRD §5 test strategy; finding F-5 applied
NEXT STEP: Review TRD §1.1-1.3 and §1.5-1.8 -- the last unexpanded
           sections. On approval, Phase 2.2 is complete and Phase 2.3
           (multi-pass review of the whole TRD) begins.
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
| 2026-10-30 | 1 | PRD amendment — Q-1/Q-2/Q-3 | **Approved** — merged `6e74e3a` |
| 2026-10-30 | 2 | `docs/02-trd/TRD.md` skeleton + expansion order | **Approved** |
| 2026-10-30 | 2 | TRD §1.4 concurrency and atomicity | **Approved** |
| 2026-10-30 | 2 | TRD §2 data models | **Approved** |
| 2026-10-30 | 2 | TRD §3 API contracts | **Approved** |
| 2026-10-30 | 2 | TRD §4 edge cases + §6 findings | **Approved** |
| 2026-10-30 | 2 | TRD §5 test strategy | **Approved** |
| 2026-10-30 | 2 | TRD §1.7 + §1.1-1.3 + §1.5, §1.6, §1.8 | Draft — awaiting review |

### Findings log

| # | Amends | Scope | Status |
|---|---|---|---|
| F-1 | §1.4.2 — unwind must not throw; `addSuppressed` | **Tier 0/1** | ✅ Applied |
| F-2 | §4.5 — batch shape errors, fail fast on semantics | Tier 0/1 | ✅ Applied |
| F-3 | §2.5 — `PurchaseStore.compareAndSetStatus` | Tier 2 | ✅ Applied |
| F-4 | PRD AC-10 → D-10, T-12 — `purchase_already_cancelled` | Tier 2 | ✅ Applied |
| F-5 | PRD T-1 — fourth assertion on gateway charge count | **Tier 0/1** | ✅ Applied, **new from §5** |

PRD now carries 10 decisions and 12 tests. F-4/F-5's PRD edits sit on
`phase/02-trd` and reach `main` when the TRD merges.

## Locked decisions

Full reasoning in PRD §9. Summarised here so a cold session does not re-litigate.

| # | Decision | Choice |
|---|---|---|
| D-1 | JSON naming | `snake_case` |
| D-2 | "Active unlimited" | Status-only, dates ignored (keeps domain clock-free) |
| D-3 | Error shape | RFC 9457 `ProblemDetail` + `code` extension |
| D-4 | Member identity | Trim + case-fold match key; store as submitted |
| D-5 | Name uniqueness beyond unlimited | None |
| D-6 | Payment failure status | **Split:** `402` `payment_declined` / `502` `payment_gateway_error` |
| D-7 | Plan reference on purchase | Snapshot, not bare `plan_id` |
| D-8 | `start_date` lower bound | Reject strictly before today; no upper bound |
| D-9 | Cancel and the unlimited slot | Cancelled frees the member immediately |

**Scope:** Tier 0 core + Tier 1 (error codes **and** the decline/outage split)
committed. Tier 2 (cancel) and Tier 3 (idempotency) gated — PRD §10.

## Open questions

**None.** All three resolved 2026-10-30 (`6e74e3a`); see PRD §13 for the full
landing map.

| # | Answer | Consequence |
|---|---|---|
| Q-1 | Reject `start_date` before today; no upper bound | A clock re-enters the system, confined to validation (D-8, AC-11, TRD §1.6) |
| Q-2 | A cancelled unlimited frees the member immediately | Tier 2 only (D-9) |
| Q-3 | Split decline `402` from outage `502` | The payment port is extended; moves from Tier 3 into committed Tier 1 (D-6, TRD §1.7) |

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
6. **Q-1 put a clock back in.** NFR-5 confines it to validation; TRD §1.6 must
   make that structural, or T-1/T-3 become time-sensitive.
7. **Q-3 extends the payment port.** TRD §1.7 picks the mechanism;
   `AlwaysSucceedingPaymentGateway` must keep compiling untouched.
8. **Cleanup parity across both payment failure modes** (TRD §4.9) — splitting one
   failure path into two is when the second one loses the cleanup guarantee.
