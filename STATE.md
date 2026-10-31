# STATE

Durable mirror of the Current State Anchor. **Read this first on every new session.**
Update it in the same turn that an artifact is approved and persisted.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 03
LAST APPROVED: step_02 (in-memory adapters, seeding, clock)
NEXT STEP: Review step_03_plans_endpoint.md. On approval ->
           step_04_payment_port_extension.md. Steps 04-08 are titles only.
==================================================
```

**Branch:** `phase/03-impl` — merges to `main` as steps are approved
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
| 2026-10-30 | 2 | TRD §1.7 + §1.1-1.3 + §1.5, §1.6, §1.8 | **Approved** |
| 2026-10-30 | 2 | TRD Phase 2.3 review, 4 passes, R-1..R-6 | **Approved** — merged `32ed602` |
| 2026-10-30 | 3 | `00_INDEX.md` 8-step decomposition + `step_01` | **Approved** |
| 2026-10-31 | 3 | `step_02_memory_stores_and_seeding.md` | **Approved** |
| 2026-10-31 | 3 | `step_03_plans_endpoint.md` | Draft — awaiting review |

### Deviations from the TRD, to record in SUBMISSION.md (step 08)

| # | Deviation | Why | Introduced |
|---|---|---|---|
| DV-1 | TRD §1.8 put `toPlan`/`parseType` as **private** methods on `SeedingConfig`; step 02 moves them to a package-private `PlanSeedMapper` | Private methods on a `@Configuration` class cannot be unit tested, and §2.6's fail-fast-on-unknown-type requirement cannot be exercised through `SeedData`, which is immutable vendor code | step 02 |

### Phase 2.3 review findings

| # | Finding | Status |
|---|---|---|
| R-1 | §1.4.2 used `plan.requireActive()`, which §1.3 explicitly rejects | ✅ Applied |
| R-2 | `tryClaim`/`release` arity mismatch across §1.4.2, §1.4.3, §1.4.7; §1.4.7's SQL `DELETE` also dropped the value-safe predicate | ✅ Applied |
| R-3 | Purchase id never generated in §1.4.2's flow | ✅ Applied (folded into step 3, no renumbering) |
| R-4 | Cancel's success response was undefined | ✅ Applied — `200` with updated purchase |
| R-5 | PRD NFR-1's "≥20 concurrent" read stale against T-1's 500 | ✅ Applied |
| R-6 | No way to mechanically verify requirement coverage | ✅ Applied — traceability matrix in §6 |

### Findings log

| # | Amends | Scope | Status |
|---|---|---|---|
| F-1 | §1.4.2 — unwind must not throw; `addSuppressed` | **Tier 0/1** | ✅ Applied |
| F-2 | §4.5 — batch shape errors, fail fast on semantics | Tier 0/1 | ✅ Applied |
| F-3 | §2.5 — `PurchaseStore.compareAndSetStatus` | Tier 2 | ✅ Applied |
| F-4 | PRD AC-10 → D-10, T-12 — `purchase_already_cancelled` | Tier 2 | ✅ Applied |
| F-5 | PRD T-1 — fourth assertion on gateway charge count | **Tier 0/1** | ✅ Applied, **new from §5** |

PRD carries 10 decisions and 12 tests. All F- and R- findings reached `main`
with the TRD merge (`32ed602`).

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
| D-10 | Double cancel | `409 purchase_already_cancelled`, not a silent idempotent `200` *(Tier 2)* |

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

## Skeleton traps — all now resolved in the TRD, carried for implementation

These were the Phase 2 carried risks. Each is settled; the column says where the
answer lives, because these are the things an implementer gets wrong by reflex.
Full detail in `context/codebase-context.md`.

| # | Trap | Resolved in |
|---|---|---|
| 1 | `@MockBean` **removed in Boot 4** — use `@MockitoBean`, or constructor injection | TRD §5.2 (mostly a stub, not an annotation) |
| 2 | No `spring-boot-starter-validation` on the classpath | TRD §1.5 — hand-rolled, starter not added |
| 3 | `remainingSlots == null` means *unlimited*, not zero (3 of 4 plans) | TRD §2.3 — absence of a counter, structurally |
| 4 | `PaymentFailedException` is unchecked — easy to leak a `500` over partial state | TRD §3.6 table + §4.2 escape-path audit |
| 5 | Charge-vs-decrement ordering with no transaction | TRD §1.4.2 — reserve → charge → commit |
| 6 | Q-1 put a clock back in | TRD §1.6 — one `Clock`, in `StartDatePolicy` only |
| 7 | Q-3 extends the payment port | TRD §1.7 — two nested subtypes, umbrella preserved |
| 8 | Cleanup parity across two payment failure modes | TRD §4.9 — one `catch (RuntimeException)`, parameterised T-2/T-10 |
| 9 | **Jackson 3 split packages** — databind at `tools.jackson.*`, annotations still `com.fasterxml.*` | TRD §3 preamble, verified from the classpath |
