# Phase 3 — Modular Implementation Plan

Sequential execution instructions for a downstream coding agent. Each step is an
independent milestone: it ends with the build green and something verifiable, and
it is reviewed before the next is written.

**Upstream:** [PRD](../01-prd/PRD.md) (approved) · [TRD](../02-trd/TRD.md)
(approved, `32ed602`) · driven by TRD §6's traceability matrix.

**Scope:** PRD Tier 0 + Tier 1 only. Tier 2 (cancel) and Tier 3 (idempotency) are
gated and get step files only if reached.

---

## The ordering decision

The obvious decomposition follows the layers inward-out — domain, stores,
service, web. It is dependency-clean and it has one serious flaw for a timeboxed
exercise: **nothing is runnable until the second-to-last step.** Run out of time
and the submission is a pile of classes with no working API.

The obvious alternative is a vertical slice per endpoint, which is runnable early
but touches the web layer three times and builds error handling in fragments.

**Neither is needed, because `GET /v1/plans` is genuinely independent of the
purchase flow.** It reads the catalogue and inventory and needs no service, no
payment, no clock, no compensation. Putting it third gives a curl-able
application at roughly the 45-minute mark without any slice-first churn — the
dependency order and the risk order agree, once you notice that one endpoint is
trivial and the other is the whole exercise.

```
step 01 ──▶ step 02 ──┬──▶ step 03   GET /v1/plans works end to end ◀── runnable
                      │
                      └──▶ step 05 ──▶ step 06 ──▶ step 07 ──▶ step 08
                              ▲
                   step 04 ───┘
```

## Steps

| # | File | Delivers | Verifiable by | Status |
|---|---|---|---|---|
| **01** | `step_01_domain_and_ports.md` | Domain records/enums, `MemberKey`, the four store ports. No Spring anywhere | compiles; `MemberKey` tests; **forbidden-import grep** | ✅ approved |
| **02** | `step_02_memory_stores_and_seeding.md` | The four in-memory adapters incl. both atomic primitives; `SeedingConfig`; `ClockConfig` | store-level atomicity tests; `bootRun` logs 4 plans; bad seed type fails the boot | ✅ approved |
| **03** | `step_03_plans_endpoint.md` | `GET /v1/plans` end to end — DTO, assembler, controller, global `snake_case` | **T-8**; `curl localhost:8080/v1/plans` returns 4 plans | ✅ approved |
| **04** | `step_04_payment_port_extension.md` | TRD §1.7's two nested subtypes + the shared `CountingGateway` fixture | additive-only vendor diff; `AlwaysSucceedingPaymentGateway` unchanged | ✅ approved |
| **05** | `step_05_purchase_service.md` | `PurchaseService` — the eight steps, the compensation stack with F-1's safe unwind, `StartDatePolicy`, `PurchaseFactory`, 7 exceptions, `ServiceConfig` | **T-4, T-5, T-9** + 7 more rules tests; **T-2, T-10** parameterised failure test | ✅ approved; F-6/F-7 applied |
| **06** | `step_06_purchase_endpoints_and_errors.md` | `POST /purchases`, `GET /purchases/{id}`, `ApiExceptionHandler` with the §3.6 table | **T-6, T-7**; every row of PRD AC-10 observable | ✅ approved |
| **07** | `step_07_concurrency_harness.md` | The virtual-thread harness, T-1/T-3 and two companions, plus hand-run mutation testing | **T-1** (5 invariants), **T-3**, T-3b/c, **T-13** | ✅ approved; F-8 applied |
| **08** | `step_08_integration_and_submission.md` | `WiringTest` + `PurchaseApiIntegrationTest`, `SUBMISSION.md`, and the packaging decision | `clean test` green; two contexts not five; zip free of planning artefacts | written |

**Why step 04 is its own step despite being ~10 lines:** it is the only change to
vendor code in the whole exercise. Keeping it in one commit means a reviewer can
see exactly what was added to `PaymentGateway` and confirm nothing was modified.
Provenance is worth a step boundary.

**Why step 07 is separate from step 05:** the concurrency harness is substantial
(§5.3), it is the single highest-weighted evaluation criterion, and it verifies
steps 02 and 05 *together* — it is an integration test of the atomicity story,
not a unit test of the service. Every other step carries its own narrower tests
inline.

## Time budget

Indicative, against the PRD's ~2.5h Tier 0 target.

| Steps | Budget | Cumulative |
|---|---|---|
| 01–02 | 35 min | 0:35 |
| 03 | 20 min | 0:55 ← runnable |
| 04–05 | 55 min | 1:50 |
| 06 | 30 min | 2:20 |
| 07 | 25 min | 2:45 |
| 08 | 20 min | 3:05 |

If the budget slips, step 07 is **not** the one to cut — it is the headline
criterion. Cut step 08's end-to-end test (the slice tests already cover the
contract) and write `SUBMISSION.md` from the documents, which is 10 minutes.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 08 (final)
LAST APPROVED: steps 01-07, plus findings F-6, F-7, F-8
NEXT STEP: Review step_08. On approval Phase 3 is complete -> merge
           phase/03-impl to main and begin Phase 4.
==================================================
```
