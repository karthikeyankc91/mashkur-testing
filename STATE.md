# STATE

Durable mirror of the Current State Anchor. Read first, write last.

```
==================================================
CURRENT PHASE: EXECUTION COMPLETE — all ten steps built, tested and merged
LAST APPROVED: Milestone 4 (cancel + idempotency), merged d491537
NEXT STEP: Nothing outstanding. The solution is submittable.
           Optional: interview dry-run against docs/05-interview-drill/DRILL.md
==================================================
```

**Branch:** `main` — all four execution milestones merged, each verified at two
parents. Working tree clean. Pushed to `origin`.

## Build health

| Check | Result |
|---|---|
| `./gradlew clean test` | **135 tests, 0 failures** |
| Live end-to-end sweep | **23 of 23 assertions pass** |
| Concurrency suite repeatability | 10 consecutive clean runs, no flakes |
| Vendor files byte-identical | `SeedData`, `AlwaysSucceedingPaymentGateway`, `MembershipsApplication`, `package-info`, `build.gradle.kts`, `settings.gradle.kts` |
| Intentional vendor edits | `PaymentGateway.java` +25, `application.yml` +7 — **0 deletions** |
| New dependencies added | none |

## Execution history

| Milestone | Branch | Steps | Merge | Delivered |
|---|---|---|---|---|
| 1 | `impl/01-foundation` | 01–03 | `94a501d` | Domain, ports, adapters, seeding, `GET /v1/plans` |
| 2 | `impl/02-purchase-flow` | 04–06 | `91954a8` | Payment taxonomy, `PurchaseService`, endpoints + error table |
| 3 | `impl/03-hardening` | 07–08 | `919f4bb` | Concurrency harness, integration tests, `SUBMISSION.md` — **core complete** |
| 4 | `impl/04-stretch` | 09–10 | `d491537` | Cancel with restore, `Idempotency-Key` |

Core shipped and merged before any stretch branch opened, so `main` held a
complete submittable solution from `919f4bb` onward.

## Findings from execution

| ID | Finding | Resolution |
|---|---|---|
| **DV-2** | Step 01 justified `strip()` over `trim()` with a non-breaking-space example. **Wrong** — `Character.isWhitespace` excludes `U+00A0`, so `strip()` leaves it exactly where `trim()` does. `"Alice"` vs `"Alice "` would have been different members, making the one-unlimited rule bypassable with an invisible character | `MemberKey` folds on `isWhitespace \|\| isSpaceChar`; recorded in `step_01` §7 |
| **DV-3** | **Mutation testing measured, not assumed.** A broken CAS was caught by the headline oversell test in only **2 of 8 runs**, but by the full suite in **5 of 5** — the failure-path test recycles 5 slots ~490 times per run. Under the mutant the counter reached **11**, above its seeded maximum | Recorded in `step_07` §8 and `SUBMISSION.md`. Retrospectively justifies `release()` not clamping: a clamp would have hidden the bug entirely |
| **DV-4** | Mutating cancel to restore-before-CAS fails 2 of 8 cancel tests — 5 callers "successfully" cancel one purchase | Confirms the ordering is load-bearing, not stylistic |
| — | Boot 4 relocated `@WebMvcTest` to `org.springframework.boot.webmvc.test.autoconfigure`, and Jackson autoconfiguration to the `spring-boot-jackson` module | Verified against the resolved classpath before writing code |
| — | My own test asserted `charges() == 5` under a declining gateway. Wrong: compensation returns each slot immediately, so ~490 contenders each acquire one | Corrected to the real invariant, with the wrong mental model documented in the test |
| — | `PurchaseControllerTest` broke when the 4-arg overload landed — the mock returned `null` and every mapped error became a 500 | Caught by the build; stubs updated and new coverage added |

## Scope delivered

Tier 0 (core) + Tier 1 (error codes) + Tier 2 (cancel) + Tier 3 (idempotency).
All four tiers.

## Known gaps, deliberate

Documented in `SUBMISSION.md`: name squatting needs authentication; no gateway
timeout; charged-with-no-record under in-memory storage; unbounded request body;
member-name normalisation stops at the edges; idempotency keys are never
evicted.
