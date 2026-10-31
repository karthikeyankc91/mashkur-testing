# PRD — Glofox Memberships Purchase API

> **STATUS: APPROVED** (merged `f54450b`) · **AMENDED 2026-10-30** — §13's three
> open questions resolved; see §13 for every section the answers touched.
> Phase 1 artifact. Scoped to functional boundaries, user stories, acceptance
> criteria, and non-functional requirements. Package layout, class design, and
> data models are deliberately absent — those are Phase 2 (TRD).

| | |
|---|---|
| **Source** | `github.com/glofoxinc/glofox-java-memberships-task` (README + skeleton, both verified in-repo) |
| **Stack** | Java 21 target (JDK 22 local), Spring Boot 4.0.0, Gradle 9.5.1, in-memory storage |
| **Timebox** | 2–3h focused; hard stop ~3–4h |
| **Real deliverable** | A submission that can be walked through and defended in a follow-up technical conversation |

---

## 1. Problem Statement

A studio owner needs to sell memberships to members. Two products exist: an
unlimited monthly plan and finite class packs. Today there is no way to take
that money — the repository contains a booted Spring application, a payment
port with no caller, an immutable table of four plans, and an empty storage
package.

The gap is the purchase transaction itself. Selling a membership means doing two
things that must both happen or neither: taking money, and consuming inventory.
One plan — Launch Promo Pack — has five slots and will be bought by more than
five people at once on launch day. The studio cannot sell a sixth, cannot
charge someone and fail to give them a membership, and cannot give away a slot
to a card that declined.

The secondary problem is evaluative: the README leaves five decisions explicitly
open and states they will be probed in conversation. An implementation that is
correct but whose decisions were never consciously made is a weaker submission
than one that is equally correct and *deliberate*.

## 2. Goals

- **G1** A member can see the plans on offer and buy one, with the amount charged
  returned to them unambiguously in integer cents.
- **G2** The six stated business rules hold, including under concurrent load.
- **G3** Money and inventory never disagree: no charge without a membership, no
  consumed slot without a charge.
- **G4** The highest-risk behaviours are covered by automated tests that fail
  loudly if the rules regress.
- **G5** Every open decision is made consciously and recorded with its reason, so
  it can be defended rather than reconstructed.
- **G6** Swapping the in-memory store for a database remains plausible without
  rewriting the business rules.

## 3. Non-Goals

Stated as refusals, not omissions. Each is a boundary to defend.

| Not doing | Why |
|---|---|
| Timezones | README puts them out of scope; `start_date` is a calendar date |
| Auth / multi-tenancy | Single studio by assignment |
| Durable persistence | In-memory by assignment; process restart loses purchases |
| Plan CRUD | Plans are seeded; README says no create-plan endpoint |
| Real payment integration | `PaymentGateway` is a port with a stub bean |
| Refunds / proration | Not in the brief; cancel (if reached) is a soft status change |
| Credit consumption | Packs carry a `credits` count; spending credits on classes is a different bounded context |
| Member registry | `member_name` *is* the identity for this exercise |
| Rate limiting, metrics, tracing | Operational concerns beyond the timebox |
| Horizontal scaling | In-process concurrency only; see NFR-4 and AS-4 |

**Anti-goal.** A broader feature surface at the cost of core correctness. The
README says this twice; it is the stated bar. A sixth promo pack sold is a
failed submission no matter what else works.

## 4. Actors & Users

| Actor | Human? | Interaction |
|---|---|---|
| **Member** | yes | Browses plans; buys one. Identified only by `member_name` |
| **Studio owner** | yes | Indirect beneficiary; inventory and revenue correctness are theirs |
| **API client** | no | Web/mobile front end. Needs machine-readable failures to show the right message |
| **Payment gateway** | no | External, fallible, and **not transactional** — the central design constraint |
| **Application bootstrap** | no | Seeds the four plans before traffic is served |
| **Reviewing engineer** | yes | Reads the code and presses on the decisions. The audience for G5 |

Listing the non-human actors matters: the gateway and the bootstrap are where the
hard requirements come from, and they are the ones usually left out.

## 5. User Stories

Priority order. Every story maps to acceptance criteria in §6.

- **US-1** As a member, I want to see the available plans with their prices, so
  that I can choose one. → AC-2
- **US-2** As a member, I want to buy a plan and be told what I was charged, so
  that I have a record of the transaction. → AC-3, AC-9
- **US-3** As a member, I want to retrieve a purchase I made, so that I can
  confirm it exists. → AC-4
- **US-4** As a studio owner, I want retired plans to be unpurchasable, so that
  nobody buys a product I no longer offer. → AC-5
- **US-5** As a studio owner, I want a member to hold at most one unlimited
  membership, so that I am not double-selling the same entitlement. → AC-6
- **US-6** As a studio owner, I want the promo pack to stop selling at exactly
  five, so that I honour the offer I advertised. → AC-7
- **US-7** As a member, I want a failed charge to leave no trace, so that I am
  not charged for a membership I do not have and the slot returns to the pool.
  → AC-8
- **US-8** As an API client, I want failures to carry a stable machine-readable
  code, so that I can show the right message rather than parse prose. → AC-10
- **US-9** As an API client, I want a declined card and an unreachable gateway to
  be distinguishable, so that I prompt for a new card in one case and retry in
  the other. → AC-8a, AC-8b
- **US-10** As a studio owner, I want memberships not to start in the past, so
  that I am not asked to honour a period that has already elapsed. → AC-11

## 6. Functional Requirements & Acceptance Criteria

Binary and observable. These become the Phase 3 verification commands.

### FR-1 / AC-1 — Plan seeding
All four `SeedData.PLANS` are loaded into the store at startup, before any
request is served. The `type` string (`"unlimited"` / `"pack"`) is mapped to a
domain type at the seeding boundary. `SeedData` remains immutable and unedited;
mutable inventory is owned by the store.

> **Verify:** boot logs four plan ids; `GET /v1/plans` returns four entries.

### FR-2 / AC-2 — `GET /v1/plans`
Returns `200` with all four plans in stable seed order, **including the inactive
Legacy Plan**. Each entry carries `id`, `name`, `type`, `active`, `price_cents`,
`credits`, `remaining_slots`.

- `credits` is `null` for unlimited plans, an integer for packs.
- `remaining_slots` is `null` for three of four plans, and **`null` means
  unlimited inventory — not zero**. Only Launch Promo Pack has a finite value.

> **Verify:** response contains 4 entries; `Legacy Plan` present with
> `active: false`; `Monthly Unlimited` has `credits: null`,
> `remaining_slots: null`; `Launch Promo Pack` has `remaining_slots: 5`.

### FR-3 / AC-3 — `POST /v1/memberships/purchases`
Accepts `{ member_name, plan_id, start_date }`. On success returns `201` with the
payload in FR-6, including the purchase id and the amount charged.

Order of evaluation is fixed, so failures are deterministic and the cheapest
checks run first:

1. **Shape** — body parses; `member_name` present; `plan_id` is a UUID;
   `start_date` is an ISO-8601 calendar date.
2. **Semantics** — `member_name` non-blank after trimming; `start_date` is not
   before today (Q-1). → AC-11
3. **Resolution** — plan exists.
4. **Plan state** — plan is active.
5. **Member state** — no existing active unlimited membership (unlimited plans).
6. **Inventory** — a slot is available (finite-inventory plans).
7. **Payment** — charge via the port.
8. **Persist** — write the purchase.

> **Verify:** valid request for Monthly Unlimited → `201`, body has `id` and
> `amount_cents: 8000`.

### FR-4 / AC-4 — `GET /v1/memberships/purchases/{id}`
`200` with the FR-6 payload for a known id. `404` for a well-formed but unknown
UUID. `400` for an id that is not a UUID.

> **Verify:** round-trip a created purchase; random UUID → `404`;
> `/purchases/not-a-uuid` → `400`.

### FR-5 — Business rules
- **(a)** Monetary amounts are integers in cents at every boundary — request,
  response, storage, gateway. Never float, never decimal string.
- **(b)** Dates are calendar dates (`YYYY-MM-DD`). No timestamps, no timezone
  conversion on `start_date`.
- **(c)** An inactive plan cannot be purchased. → AC-5
- **(d)** A member holds at most one *active unlimited* membership. → AC-6
- **(e)** Finite inventory is never oversold and never negative. → AC-7
- **(f)** A failed payment leaves no purchase and no inventory change. → AC-8

### AC-5 — Inactive plan rejected
Purchasing Legacy Plan (`4444…`) returns `409` with code `plan_inactive`. No
charge is attempted; no purchase is created.

### AC-6 — One active unlimited per member
A second Monthly Unlimited purchase for the same member returns `409` with code
`unlimited_membership_exists`. The same member *may* hold multiple packs, and may
hold packs alongside one unlimited. Member identity is the trimmed,
case-folded `member_name` (D-4), so `"alice"`, `"Alice"` and `" Alice "` are one
member.

A `CANCELLED` unlimited membership does **not** block a new purchase (Q-2): the
rule reads "active", and cancelled is not active. Only relevant if Tier 2 is
reached. → T-11

### AC-7 — Promo inventory honoured
Launch Promo Pack sells exactly five times. The sixth attempt returns `409` with
code `plan_sold_out`. `remaining_slots` in `GET /v1/plans` decreases by exactly
one per success and never drops below zero. Plans with `remaining_slots: null`
are purchasable without limit.

### AC-8 — Payment failure leaves nothing behind
Whenever a charge does not succeed — for **either** failure mode below — **all
three** pieces of mutable state are left as they were: no purchase is
retrievable by any id, `remaining_slots` for the targeted plan is unchanged from
its pre-request value, and **the member's unlimited claim is released**.

The third clause was added by TRD finding F-7, and the omission mattered more
than it looks: an unlimited plan has no inventory counter at all, so a service
that claimed the member's unlimited slot and never released it would satisfy the
first two clauses while permanently barring that member from ever buying an
unlimited membership again.

- **AC-8a — Declined.** The card was refused: a definitive answer from a working
  gateway. Responds `402` / `payment_declined`. Not retryable as-is.
- **AC-8b — Gateway unavailable.** The gateway could not be reached or errored
  before deciding: no answer at all. Responds `502` / `payment_gateway_error`.
  Retryable. (Q-3)

The distinction is the client's: a decline needs a different card, an outage
needs the same request again later. Collapsing both into `402` tells the client
to do the wrong thing half the time.

**This requires extending the port.** As shipped, `PaymentFailedException(String)`
is the only failure channel, so the two modes are indistinguishable — PRD AS-6
previously recorded that as a hard limit. The README lists "distinguish payment
decline vs gateway outage" as a stretch goal, which sanctions the change. The
PRD requires the distinction; the TRD chooses the mechanism (exception subtype
versus a new port exception) and must keep
`AlwaysSucceedingPaymentGateway` compiling untouched.

### FR-6 / AC-9 — Purchase payload
`id`, `member_name` (as submitted, preserving the member's own casing for
display), a plan snapshot (`plan_id`, `name`, `type`, `price_cents`, `credits`),
`amount_cents`, `status`, `start_date`, `transaction_id`, `created_at`.

Two deliberate choices:
- **Snapshot, not a bare `plan_id`.** A later price change must not retroactively
  rewrite what a member was charged. The purchase is a historical record.
- **`transaction_id` is retained** from `PaymentResult`, because a charge with no
  local reference is unreconcilable.

`status` is `ACTIVE`, or `CANCELLED` if Tier 2 is reached. There is no `FAILED`
status: by FR-5(f) a failed purchase never persists.

### FR-7 / AC-10 — Error contract
RFC 9457 Problem Details (`application/problem+json`), with a `code` extension
property carrying a stable machine-readable value.

| Status | Condition | `code` |
|---|---|---|
| `400` | malformed JSON, non-UUID id, unparseable date, blank `member_name` | `validation_failed` |
| `400` | `start_date` before today | `start_date_in_past` |
| `404` | unknown `plan_id` | `plan_not_found` |
| `404` | unknown purchase id | `purchase_not_found` |
| `409` | plan is inactive | `plan_inactive` |
| `409` | member already holds an active unlimited | `unlimited_membership_exists` |
| `409` | finite inventory exhausted | `plan_sold_out` |
| `409` | cancelling an already-cancelled purchase *(Tier 2)* | `purchase_already_cancelled` |
| `402` | charge declined by a working gateway | `payment_declined` |
| `502` | gateway unreachable or errored before deciding | `payment_gateway_error` |
| `500` | anything unanticipated | `internal_error` |

`start_date_in_past` gets its own code rather than sharing `validation_failed`,
because a client can act on it (offer the user today's date) where a malformed
body is a programming error.

All three business-rule violations share `409`: the request is well-formed and
references real resources, but conflicts with current state. They are
distinguished by `code`, not by status — so a client switches on one stable
field rather than on an HTTP number that may be reused.

### AC-11 — `start_date` in the past is rejected
A `start_date` strictly before today returns `400` with code
`start_date_in_past`. Today and any future date are accepted; no upper bound is
imposed. No charge is attempted and no purchase is created. (Q-1)

**"Today" is read from an injectable clock, and only here.** Validation may read
the clock; business rules may not (NFR-5). D-2's status-only active rule is
unaffected, which is what keeps the concurrency tests time-independent.

> ⚠️ **The README's own example payload uses `start_date: "2026-09-01"`, which is
> now in the past.** Under this rule the brief's documented sample request
> returns `400`. This is called out in `SUBMISSION.md` rather than worked around,
> since silently accepting past dates to keep one stale example working is the
> worse trade.

## 7. Functional Boundaries

| Concern | In scope | Out of scope |
|---|---|---|
| Plans | Read seeded plans, including inactive | Create / update / delete |
| Purchases | Create, fetch by id | List, filter, paginate — a README stretch item taken into no tier |
| Payment | Charge through the port; distinguish decline from outage | Refund, automatic retry, capture/auth split |
| Inventory | Decrement on success; never oversell | Restock, waitlist, reservation expiry |
| Membership lifecycle | `ACTIVE` on purchase | Expiry, renewal, freeze, upgrade; credit consumption |
| Identity | `member_name` as key | Registration, dedupe, PII handling |
| Concurrency | Correct within one process | Cross-instance coordination |

## 8. Non-Functional Requirements

### NFR-1 — No oversell under concurrency
With five promo slots and **at least 20 concurrent** purchase requests released
from a common barrier: exactly 5 return `201`, the remainder return `409
plan_sold_out`, and `remaining_slots` settles at exactly `0`. Never negative.
Never `>0` while requests were rejected. Repeatable, not flaky.

Twenty is the floor this requirement sets; T-1 uses **500** virtual threads,
because more contenders on five slots materially widens the chance of hitting a
narrow interleaving and Java 21 makes them cheap.

### NFR-2 — Atomicity without transactions
In-memory storage provides no rollback, and the gateway cannot enlist in one.
Therefore **the ordering of charge and inventory mutation is the consistency
story**, and it must be an explicit decision rather than an accident of
statement order.

The guarantee: no externally observable state may exist in which payment
succeeded but no purchase is retrievable, or inventory was consumed but no
purchase exists. The PRD fixes the guarantee; the TRD selects the mechanism and
justifies it.

### NFR-3 — One-unlimited rule is also a race
Two simultaneous Monthly Unlimited purchases for the same member must yield
exactly one `201` and one `409 unlimited_membership_exists`.

> The README mentions concurrency only for promo slots, but "check for an
> existing unlimited, then insert" is the same check-then-act race with the same
> failure mode. Guarding only the counter leaves the rule that *looks* safe
> broken.

### NFR-4 — Swappable storage
Stores sit behind ports; the in-memory implementation lives under
`storage/memory`. No Spring or web types appear in the domain. Concurrency
primitives must not leak an in-memory assumption into the business rules — the
service must express *what* must be atomic, leaving *how* to the store, so that
a database-backed implementation substituting row locks or a unique constraint
is a drop-in.

### NFR-5 — Testability and determinism
- The gateway is replaceable per test to simulate failure. **Spring Boot 4
  removed `@MockBean`** — use `@MockitoBean`, or a `@TestConfiguration`
  contributing a `@Primary` failing implementation.
- **No business rule reads a wall clock.** The clock is injectable and reachable
  from *validation only* (AC-11's past-date check). D-2's status-only active rule
  stays clock-free, which is what keeps T-1 and T-3 time-independent. Validation
  tests pin a fixed clock; concurrency tests need no clock control at all.
  Collapsing this boundary is how a clock-free domain silently becomes
  time-sensitive.
- Concurrency tests use an explicit barrier, not `Thread.sleep`.

### NFR-6 — Rule placement
Business rules live in the service layer. Controllers do request-shape
validation and status mapping, and hold no rules. Stores hold storage and the
atomic primitives they own, and no policy. "Where the rules live" is a stated
evaluation criterion, so the PRD fixes the intent and the TRD defends it.

### NFR-7 — Timebox discipline
Core complete and green by ~2.5h. Stretch tiers are gated (§10) and submission is
never blocked on one. An unreached tier is recorded as a known gap.

### NFR-8 — Readability over cleverness
"Simple and clear beats clever and opaque" is an explicit evaluation criterion.
Where a simpler construct and a faster one differ, prefer the simpler and note
the trade-off. The code must be walkable aloud under questioning.

### NFR-9 — Startup observability
Seeded plan ids are logged at boot (already in the skeleton) so a client can
discover valid ids without reading source.

## 9. Locked Decisions

The README's five open decisions, plus two the skeleton forces. Recorded with
reasons — this section is the source for `SUBMISSION.md`.

| # | Decision | Choice | Why |
|---|---|---|---|
| **D-1** | JSON naming | `snake_case` throughout | Matches the README's own example payload; consistency matters more than the choice |
| **D-2** | "Active unlimited" | Status-only; dates ignored | Keeps the domain clock-free and tests deterministic. A date-windowed rule needs an injectable `Clock` and makes every concurrency test time-sensitive — the worst pairing. The expiry gap is declared in `SUBMISSION.md`, not half-built |
| **D-3** | Error shape | RFC 9457 `ProblemDetail` + `code` | Spring Boot 4 ships it and wires it through `@ExceptionHandler`; standards-based with near-zero custom code. The `code` extension restores the machine-readable value `ProblemDetail` alone lacks |
| **D-4** | Member identity | Trim + case-fold the match key; store as submitted | Without it, `"alice"` bypasses the one-unlimited rule that `"Alice"` is subject to — a hole a reviewer will test immediately. Display value preserved because the member typed it |
| **D-5** | Name uniqueness beyond unlimited | None | A member may hold many packs, and packs alongside one unlimited. Only FR-5(d) constrains |
| **D-6** | Payment failure status | **Split:** `402` `payment_declined` for a decline, `502` `payment_gateway_error` for an outage | The two mean opposite things to a client: try another card versus retry this one. Requires extending the port, which the README sanctions as a stretch goal. *Alternative considered:* `503` + `Retry-After` for the outage — defensible, and arguably better, but `502` reads more plainly as "the upstream failed" |
| **D-7** | Plan reference on purchase | Snapshot | A purchase is a historical record; a price change must not rewrite history |
| **D-8** | `start_date` lower bound | Reject strictly before today; no upper bound | Backdating a membership is a billing decision, not a purchase one. Cost: a clock enters validation (NFR-5), and the README's own stale example now fails (AC-11) |
| **D-9** | Cancel and the unlimited slot | A cancelled unlimited frees the member immediately | The rule reads "active"; `CANCELLED` is not active. Any other reading needs a cooling-off concept nothing in the brief supports |
| **D-10** | Double cancel | `409 purchase_already_cancelled`, not a silent idempotent `200` | A client cancelling twice has a bug; hiding it helps nobody. Added by TRD finding F-4, which also showed the status transition must be a CAS — two concurrent cancels would otherwise both restore a slot and leave a 5-slot pack with 6 *(Tier 2 only)* |

## 10. Scope Tiers

Gated rather than committed flat, because the README states the ceiling twice and
says core completeness beats volume.

| Tier | Content | Gate |
|---|---|---|
| **0 — MUST** | Three endpoints, seeding, FR-5(a–f), AC-11 past-date rejection, NFR-1/2/3, high-risk tests, `SUBMISSION.md` | Green by ~2.5h |
| **1 — SHOULD** | Machine-readable error `code` values; decline-vs-outage split (D-6) | Both committed. Codes ride on D-3; the split is one exception type and one handler branch |
| **2 — COULD** | `POST /{id}/cancel`, soft cancel, promo inventory restore, D-9 slot release | Tier 0 green |
| **3 — COULD** | `Idempotency-Key` on purchase | Tier 2 green **and** ≥1h left |

**Q-3 moved the decline/outage split out of Tier 3.** It was parked there while
the port was assumed immutable; once extending the port is on the table it costs
one exception type and one `@ExceptionHandler` branch, so it joins the committed
scope. `AlwaysSucceedingPaymentGateway` must keep compiling untouched.

Tier 3 remains last: `PaymentGateway.charge(int, String)` takes no idempotency
parameter, so deduplication lives entirely in the service and introduces a
*second* race that interacts with the inventory race. Highest cost, highest risk
to Tier 0.

## 11. Test Strategy

Risk-ordered. The brief asks which tests were chosen and why, so the ordering is
itself part of the submission.

| # | Test | Risk it retires |
|---|---|---|
| **T-1** | Promo oversell: 500 virtual threads released from one start gate onto 5 slots. Asserts **four** invariants — exactly 5 × `201`; all others `409 plan_sold_out`; final `remaining_slots == 0`; **gateway charged exactly 5 times** | The headline requirement and an explicit evaluation criterion. The charge count is not redundant: an implementation that charged all 500 cards and persisted only 5 satisfies the other three (TRD F-5) |
| **T-2** | Payment decline: no purchase persisted, inventory unchanged, **and the member's unlimited claim released** — proven by a retry for the same member succeeding | FR-5(f); the exception is unchecked, so the default failure mode is a leaked `500` over partial state. The claim clause is F-7: without it the test passes while the member is permanently barred, because unlimited plans have no inventory counter |
| **T-3** | Concurrent duplicate-unlimited, same member → exactly one `201` | NFR-3, the race the brief does not mention |
| **T-4** | `remaining_slots: null` is unlimited — repeated purchases of `10-Class Pack` succeed | Highest-probability one-character bug: an inventory check written `remaining > 0` makes three of four plans unpurchasable |
| **T-5** | Legacy Plan → `409 plan_inactive`, no charge attempted | FR-5(c) |
| **T-6** | Purchase round-trip; unknown UUID → `404` | FR-4 |
| **T-7** | Validation matrix: blank name, whitespace-only name, bad UUID, malformed date | FR-3 step 1–2 |
| **T-8** | `GET /v1/plans` returns 4 including the inactive plan | FR-2; easy to filter inactive out by reflex |
| **T-9** | `start_date` of yesterday → `400 start_date_in_past`; today → `201` | AC-11, with a pinned clock. The today boundary is the off-by-one |
| **T-10** | Gateway outage → `502 payment_gateway_error`, with the **identical three-part cleanup** asserted as T-2 | AC-8b. Written as one parameterised test with T-2, so the second failure mode cannot skip a cleanup assertion the first one makes |
| **T-11** | Cancel an unlimited, then repurchase → `201` *(Tier 2 only)* | D-9 |
| **T-12** | Concurrent double-cancel of one promo purchase: one `200`, one `409 purchase_already_cancelled`, slot restored **exactly once** *(Tier 2 only)* | D-10 / TRD F-3 — the restore path has the same check-then-act race as the decrement, and only the counter's final value catches it |

T-1 through T-4 are the submission's spine. T-5 through T-10 are cheap and fast.
T-11 and T-12 exist only if Tier 2 is reached — and T-12 is the one that matters,
since T-11 would pass even with the F-3 bug present.

The pairing of T-2 and T-10 is deliberate: splitting one failure path into two
is exactly when the cleanup guarantee on the second path gets forgotten.

## 12. Assumptions

Each is a question nobody answered. All go into `SUBMISSION.md` verbatim, because
an unflagged assumption becomes an undefended decision in the follow-up.

- **AS-1** Unlimited memberships never expire under D-2. Real billing would need a
  period and a clock.
- **AS-2** `member_name` is the whole identity. No registry, no collision
  handling — two real people named Alice are one member here.
- **AS-3** Pack `credits` are recorded but never decremented; class attendance is
  a separate context.
- **AS-4** Concurrency is in-process only. A second instance would oversell —
  correctness here depends on single-process ownership of the store, which is
  exactly what moving to a database would fix.
- **AS-5** `start_date` may be today or any future date, with **no upper bound** —
  a membership starting in 2099 is accepted. Only the past is rejected (D-8).
  Capping the future would be inventing a requirement.
- **AS-6** The gateway is idempotency-unaware; the port exposes no key, so a
  retry double-charges. *(Revised by Q-3: the port no longer hides the
  decline/outage distinction — it is extended to expose it. Idempotency remains
  unexposed.)*
- **AS-7** `GET /v1/plans` returning inactive plans is intentional per the README;
  clients filter for display.
- **AS-8** "Today", for AC-11, is the server's default timezone. The README puts
  timezones out of scope, so D-8 creates a timezone question the brief declared
  out of bounds — a member near midnight in another zone may see a date rejected
  that is still today for them. Accepted for a single-studio exercise; a real
  system would resolve "today" in the studio's zone.
- **AS-9** Extending `PaymentGateway` is treated as permitted, on the strength of
  the README listing decline-vs-outage as a stretch goal. The default bean is
  left untouched.

## 13. Resolved Questions

All three answered 2026-10-30. None outstanding.

| # | Question | Answer | Landed in |
|---|---|---|---|
| **Q-1** | Reject `start_date` in the past? | **Yes** — strictly before today, no upper bound | D-8, AC-11, FR-3 step 2, T-9, AS-5, AS-8 |
| **Q-2** | Does cancelling an unlimited free the member immediately? | **Yes** — `CANCELLED` is not active | D-9, AC-6, T-11, Tier 2 |
| **Q-3** | Split decline from gateway outage? | **Yes** — `402` vs `502` | D-6, AC-8a/8b, FR-7 table, T-10, AS-6, AS-9, Tier 1 |

Two consequences worth carrying into the TRD, because each reverses something
the first draft asserted:

1. **A clock re-enters the system** (Q-1). Confined to validation by NFR-5. The
   TRD must make that confinement structural rather than a convention, or the
   concurrency tests become time-sensitive.
2. **The payment port is no longer immutable** (Q-3). AS-6 and D-6 originally
   recorded the decline/outage distinction as impossible. The TRD chooses the
   mechanism and must keep `AlwaysSucceedingPaymentGateway` compiling untouched.

---

```
==================================================
CURRENT PHASE: Phase 2 [Technical Requirement Document] -> Sub-Phase 2.1
LAST APPROVED: This PRD, as amended by the Q-1/Q-2/Q-3 answers
NEXT STEP: TRD skeleton review, then Phase 2.2 expanding §1.4 first
==================================================
```
