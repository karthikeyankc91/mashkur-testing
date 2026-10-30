# TRD — Glofox Memberships Purchase API

> **STATUS: Phase 2.2 in progress.** Skeleton approved.
> **§1.4 expanded — awaiting review.** All other sections remain headings plus
> the question they must answer. Sections are expanded **one at a time**, each
> pausing for approval.

**Upstream:** [`docs/01-prd/PRD.md`](../01-prd/PRD.md) (approved `f54450b`, amended
`6e74e3a` — Q-1/Q-2/Q-3 resolved) ·
[`context/codebase-context.md`](../../context/codebase-context.md) (verified skeleton)

**Scope of this document:** how the PRD's requirements are built. The PRD fixed
*what* and *why*; this fixes *how*. Implementation step files are Phase 3.

---

## 1. Architecture

### 1.1 Layering and package layout
Resolves README open decision #2. What packages exist, what each owns, and what
each is forbidden from importing.

### 1.2 Dependency direction and the port boundary
How NFR-4 (swappable storage) is enforced rather than merely intended — which
direction dependencies point, and what keeps Spring and web types out of the
domain.

### 1.3 Where the business rules live
NFR-6 and evaluation criterion #2. Why rules sit in the service and not the
controller or the store, and what the handler is left holding.

### 1.4 Concurrency model and the atomicity boundary ⭐

Resolves NFR-1, NFR-2, NFR-3. Everything downstream depends on this, so it is
expanded first.

#### 1.4.1 What must be atomic — and what need not be

The purchase flow touches five things. Only two of them are contended:

| Step | Contended? | Why |
|---|---|---|
| Resolve plan by id | **No** | The catalogue is immutable after seeding |
| Check plan is active | **No** | `active` never changes — there is no plan CRUD |
| Claim the member's unlimited slot | **Yes** | Check-then-act across threads (NFR-3) |
| Reserve a finite inventory slot | **Yes** | Check-then-act across threads (NFR-1) |
| Charge, then persist the purchase | **No** | A charge is per-request; the purchase id is unique per request |

This is the first payoff of splitting catalogue from inventory (§2.2): the
contended surface shrinks to **two atomic claims**. Plan lookup and the active
check need no synchronization at all, because they read data that cannot change.
Guarding them would cost throughput and buy nothing.

> Stated plainly for the follow-up: *"Only two things race. Everything else is a
> read of immutable data."*

#### 1.4.2 Ordering: reserve → charge → commit

The decision NFR-2 demands. Payment is an unrollbackable external effect, so
the only question that matters is **which side of the charge the inventory
mutation sits on.**

**Charge first, then reserve — rejected outright.** Charge succeeds, reservation
finds the pack sold out, and a member has paid for nothing. There is no refund
in scope and no refund in the port. This ordering can take money for a product
it cannot deliver, so it is not a trade-off, it is a defect.

**Reserve first, then charge — chosen.**

```
1. plan   = catalogue.find(planId)              // 404 plan_not_found
2.          plan.requireActive()                // 409 plan_inactive
3. claims = []                                  // compensation stack
4.          if plan is unlimited:
              tryClaim(memberKey)   or 409 unlimited_membership_exists
              claims.push(releaseClaim)
5.          if plan has finite inventory:
              tryReserve(planId)    or 409 plan_sold_out
              claims.push(releaseSlot)
6. result = gateway.charge(amountCents, memberName)   // no lock held
                                                 // 402 declined / 502 outage
7.          purchases.save(purchase)
8. return purchase
   ---- on ANY throw after step 3: unwind claims in reverse, rethrow ----
```

Steps 4 and 5 are each a single atomic operation, not a held lock. Nothing is
locked during step 6, which is the only slow step.

#### 1.4.3 Two claims, one pattern

The promo counter and the one-unlimited rule look like different problems and
are the same problem: *acquire an exclusive thing, or fail.* Treating them as one
pattern is why NFR-3 gets solved for free rather than forgotten.

| | Inventory (NFR-1) | Unlimited slot (NFR-3) |
|---|---|---|
| Claim | `tryReserve(planId) -> boolean` | `tryClaim(memberKey) -> boolean` |
| Release | `release(planId)` | `release(memberKey)` |
| Exhausted when | counter would go below zero | key already present |
| Released by | payment failure | payment failure, and cancel (D-9) |

Both are **claim-or-fail with a compensating release**, which keeps the service
free of any locking vocabulary. Provisional signatures; §2.5 finalises them.

#### 1.4.4 Mechanism

**Inventory** — an `AtomicInteger` per finite-inventory plan:

```java
int before = slots.getAndUpdate(n -> n > 0 ? n - 1 : n);
return before > 0;                       // claimed iff it was positive
```

Lock-free, two lines, and structurally incapable of going negative — the guard
is inside the update function, so there is no window between test and decrement.
`getAndUpdate` rather than `updateAndGet` because the *previous* value is what
tells you whether you won.

**Unlimited slot** — `ConcurrentHashMap.putIfAbsent(memberKey, purchaseId)`,
which returns `null` exactly once per key. One atomic call, no loop.

**The null-inventory guard is structural, not a conditional.** Plans with
`remaining_slots == null` get **no counter at all**. Absence from the inventory
map *is* unlimited inventory, so `tryReserve` short-circuits to `true` when there
is no entry. This retires PRD T-4 by construction: there is no `remaining > 0`
comparison to write backwards for the three plans where it would be wrong.

#### 1.4.5 Rejected alternatives

**A lock held across the whole flow** (per-plan `synchronized`, or one global
lock). Simplest to explain and obviously correct — and it holds a lock across a
network call. Throughput collapses to one purchase per gateway round-trip, and a
hanging gateway makes the plan unpurchasable for everyone rather than slow for
one. Rejected on NFR-8 grounds too: the simplicity is real but it buys a
liveness failure that is harder to defend than a two-line CAS.

**Two-phase reserve/commit with reservation records and TTLs.** The correct
answer for a distributed system and far past the timebox. Named here because
"what would you do with real infrastructure?" is a likely follow-up.

**A generic reservation framework** abstracting both claims behind one
interface. Tempting — the pattern genuinely is identical. Rejected: two concrete
stores with four plain methods are more readable than one abstraction with a
type parameter, and §1.4.3 already captures the shared pattern in prose where it
costs nothing. Clever and opaque loses to simple and clear.

#### 1.4.6 Accepted windows, stated not hidden

The ordering closes the dangerous window and leaves two small ones. Both are
named here so they are defended rather than discovered.

**W-1 — transient inventory dip.** Between step 5 and the unwind, a reserved slot
is held while the charge is in flight. A concurrent `GET /v1/plans` can observe
`remaining_slots: 4` for a purchase that ultimately fails and returns the slot.
AC-8 constrains the *end* state, which holds. This is the price of never
overselling, and it is the right side of that trade: a buyer briefly told "sold
out" can retry, a buyer charged for nothing cannot be made whole.

**W-2 — charged but not persisted.** If step 7 fails after step 6 succeeded,
money has moved and no purchase exists. The unwind correctly returns the slot,
but cannot return the money. In-process with a `ConcurrentHashMap`, step 7 has
no realistic failure mode short of OOM, and a crash loses all state anyway since
storage is in-memory. **The honest answer is that this window cannot be closed
without infrastructure** — an idempotency key on the charge, a transactional
outbox, or a reconciliation job against the gateway. Saving a `PENDING` purchase
*before* charging would trade this for a status PRD FR-6 explicitly rules out
and a briefly-`GET`table record AC-8 forbids, so it is not an improvement within
this contract.

**W-3 — reservation leak.** A thread killed between step 5 and the unwind leaks a
slot permanently. Mitigated in a real system by reservation TTLs; out of scope
here, and worth saying so before being asked.

#### 1.4.7 Why a database store drops in (NFR-4 proof)

NFR-4 asks for plausibility, which is only demonstrated if the port shape has a
direct SQL analogue. Both do:

| Port operation | SQL equivalent | Atomicity from |
|---|---|---|
| `tryReserve(planId)` | `UPDATE plans SET remaining_slots = remaining_slots - 1 WHERE id = ? AND remaining_slots > 0` | rows-affected = 1 |
| `release(planId)` | `UPDATE plans SET remaining_slots = remaining_slots + 1 WHERE id = ?` | single statement |
| `tryClaim(memberKey)` | `INSERT INTO unlimited_claims(member_key) VALUES (?)` | unique constraint violation = lost |
| `release(memberKey)` | `DELETE FROM unlimited_claims WHERE member_key = ?` | single statement |

The service expresses *what* must be atomic — claim or fail — and never *how*.
Swapping the store substitutes a conditional `UPDATE` and a unique index for a
CAS and a `putIfAbsent`, with no change to the business rules. That is the
concrete content of "no in-memory assumption leaks into the service."

#### 1.4.8 What this section commits the tests to

- **T-1** asserts the final counter is exactly `0`, not merely that five requests
  succeeded. The success count can be right while the counter is wrong.
- **T-3** is not optional: it is the only test that exercises `tryClaim` under
  contention, and NFR-3 is the requirement the brief never states.
- **T-2 and T-10** must both assert inventory is restored, since §1.4.2's unwind
  is one code path reached by two exception types (§4.9).
- No test needs a clock. §1.6 keeps it that way.

### 1.5 Validation strategy
Hand-roll, or add `spring-boot-starter-validation` to a deliberately three-dependency
build? Weighed against "clarity over frameworks for their own sake."

### 1.6 The clock boundary (Q-1)
AC-11 needs "today"; NFR-5 forbids business rules from reading a clock. How the
`Clock` is injected and reachable from validation **only**, enforced structurally
rather than by convention — so the domain cannot acquire a time dependency by
accident and leave T-1/T-3 time-sensitive.

### 1.7 Payment port extension (Q-3)
AC-8a/8b require distinguishing a decline from an outage, which the port as
shipped cannot express. Subtype of `PaymentFailedException`, a sibling exception,
or a result type? Constraint: `AlwaysSucceedingPaymentGateway` must keep
compiling untouched, and the change must read as a deliberate extension of
vendor code rather than an edit to it.

### 1.8 Startup seeding and bean wiring
How FR-1's "before traffic is served" is guaranteed, and where the
`PlanSeed → domain` mapping lives.

---

## 2. Data Models

### 2.1 Domain types
The records and enums, their fields, and which are immutable.

### 2.2 Catalogue versus inventory
`SeedData` is immutable, so mutable `remaining_slots` cannot live on the plan
record. Where the split falls and what each half owns.

### 2.3 Inventory representation
What type holds remaining slots, and how `null = unlimited` is encoded so it
cannot be mistaken for zero (PRD T-4). Candidate: make the absence explicit in
the type rather than carrying a nullable integer.

### 2.4 Member identity key
How D-4's trimmed, case-folded key is derived and stored alongside the
as-submitted display name — and where that derivation lives so it cannot be
bypassed.

### 2.5 Store port contracts
The port method signatures. **Shaped by §1.4** — the atomic operations the
service needs are what these methods must be.

### 2.6 Seed mapping
`PlanSeed(String type)` → domain enum, and what happens on an unrecognised value.

---

## 3. API Contracts

### 3.1 DTOs and the `snake_case` mapping
Request/response types, and how D-1 is applied — per-field annotation versus a
global naming strategy.

### 3.2 `GET /v1/plans`
Exact response payload, field by field, with the null cases from PRD AC-2.

### 3.3 `POST /v1/memberships/purchases`
Exact request and `201` response, mapped to PRD FR-6.

### 3.4 `GET /v1/memberships/purchases/{id}`
Response and the `400`-vs-`404` boundary for a malformed id.

### 3.5 Problem Details shape
D-3 concretely: the `ProblemDetail` fields used, how the `code` extension is
attached, and the `type` URI convention.

### 3.6 Exception-to-status mapping
How PRD AC-10's ten-row table is implemented — which exceptions exist, where they
are translated, and how an unchecked `PaymentFailedException` is prevented from
escaping as a `500`. Includes the `402`/`502` split from §1.7 and the distinct
`start_date_in_past` code.

---

## 4. Edge Cases

### 4.1 `remaining_slots: null` versus `0`
The one-character bug (PRD T-4), and the type-level guard against it.

### 4.2 Unchecked exception escape paths
Every path by which `PaymentFailedException` could bypass handling.

### 4.3 Partial-failure windows
Enumerated against the §1.4 ordering: what is observable if the process dies at
each step, and which windows are accepted versus closed.

### 4.4 Concurrent same-member unlimited
NFR-3 concretely: what two racing threads each see, and where they serialise.

### 4.5 Validation ordering
Why PRD FR-3's eight steps run in that order, and what a client sees when more
than one would fail.

### 4.6 Cancel and inventory restore (Tier 2)
Restore semantics, double-cancel, and whether cancelling frees the one-unlimited
slot (PRD Q-2).

### 4.7 Retry and double-charge (Tier 3)
Why the port's missing idempotency parameter pushes dedupe into the service, and
how that second race interacts with §1.4.

### 4.8 The `start_date` today boundary (Q-1)
Yesterday rejected, today accepted — the off-by-one, and what "today" means when
the request crosses midnight mid-flight (PRD AS-8).

### 4.9 Cleanup parity across both payment failure modes (Q-3)
Splitting one failure path into two is exactly when the second path silently
loses the no-purchase/no-inventory-change guarantee. How both branches are made
to share one cleanup path rather than two that must be kept in step.

---

## 5. Test Strategy

### 5.1 Test levels
Unit versus `@WebMvcTest` slice versus full `@SpringBootTest`, and which of PRD
T-1…T-8 lands at which level — chosen for speed and precision, not symmetry.

### 5.2 Gateway substitution
The Boot 4 mechanism (`@MockitoBean` or `@TestConfiguration` + `@Primary`) and
why `@MockBean` is unavailable.

### 5.3 Concurrency harness
Barrier design, thread count, and the assertions that make NFR-1 non-flaky —
including asserting the *final* inventory value, not just the success count.

### 5.4 Clock control in tests
How T-9 pins a fixed clock for the past-date boundary while T-1 and T-3 need no
clock control at all — the observable payoff of §1.6.

### 5.5 Coverage map
PRD T-1…T-11 → test classes and method names.

### 5.6 Deliberately untested
What is left uncovered inside the timebox, and why that is the right trade — this
answers evaluation criterion #4 directly.

---

## 6. Risks and Deferred Items

Carried from PRD §13 and `STATE.md`, plus anything surfaced while expanding the
sections above.

---

## Proposed expansion order

**Not document order.** §1.4 determines the store port contracts (§2.5), which
determine the service shape (§1.3), which determine the error paths (§3.6).
Expanding top-to-bottom would mean rewriting §1.1–1.3 once §1.4 settles.

| Order | Section | Why here |
|---|---|---|
| 1 | **§1.4** Concurrency and atomicity | Everything downstream depends on it. Also the hardest interview question |
| 2 | **§2** Data models | Falls out of §1.4 once the atomic unit is known |
| 3 | **§1.1–1.3, §1.5–1.8** Remaining architecture | Layout follows the types, not the reverse. §1.7 (port extension) is independent and can move earlier if you want it settled sooner |
| 4 | **§3** API contracts | Mechanical once the domain exists |
| 5 | **§4** Edge cases | An audit of 1–4; finds gaps while they are still cheap |
| 6 | **§5** Test strategy | Needs the final shape to map tests onto |
| 7 | **§6** Risks | Accumulated throughout; closed last |

```
==================================================
CURRENT PHASE: Phase 2 [Technical Requirement Document] -> Sub-Phase 2.2
LAST APPROVED: TRD skeleton + expansion order
NEXT STEP: Review §1.4 (concurrency and atomicity). On approval -> §2
           (data models), which its port shapes now determine.
==================================================
```
