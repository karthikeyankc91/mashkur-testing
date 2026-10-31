# Interview Drill — Glofox Memberships

**Phase 5 artifact.** Not a summary of the other documents — a defence brief. Everything here
is designed to be said out loud.

Four of the brief's six evaluation criteria are about reasoning, not output. This is the
reasoning, compressed.

---

## 1. The opener — say this when asked "walk me through your solution"

> "It's three endpoints over in-memory storage, but the whole exercise is really one
> transaction: take money and consume inventory, with no transaction manager available. So
> I'll start there.
>
> **Two things in the system race** — the promo pack's five slots, and the rule that a member
> holds at most one unlimited membership. Everything else is a read of immutable data: the
> plan catalogue never changes after seeding, so plan lookup and the active check need no
> synchronisation at all.
>
> **The ordering is reserve, then charge, then commit.** Charging first isn't a trade-off,
> it's a defect — it can take money and *then* discover the pack is sold out, and there's no
> refund in the port. So I claim atomically, charge with nothing locked, then persist. Any
> failure after the first claim unwinds a compensation stack in reverse.
>
> **The two claims turned out to be the same pattern** — claim-or-fail with a compensating
> release — which is why the one-unlimited rule is concurrency-guarded too. The brief only
> asks about the promo slots."

**Do not open with the package layout.** Open with the transaction. The layout is the answer
to a different question, and leading with it signals you organised files rather than solved a
problem.

## 2. Numbers to have ready

| | |
|---|---|
| Plans seeded | 4 — one inactive (Legacy), one with finite inventory (Launch Promo, 5 slots) |
| Prices (cents) | 8000 unlimited · 5000 ten-pack · 2500 promo · 6000 legacy |
| T-1 contenders | **500 virtual threads** on 5 slots, one start gate |
| Tests | 13 specified; T-1–T-4 are the spine |
| Decisions logged | 10, each with its reason |
| Vendor files touched | **2**, both additive: `application.yml` (one key), `PaymentGateway.java` (three nested classes) |
| Dependencies added | **0** — still the original three |
| Stack | Java 21 target, Spring Boot 4.0.0, Gradle 9.5.1, Jackson 3 |

## 3. The ten decisions, one line each

| | Decision | The defence |
|---|---|---|
| D-1 | `snake_case`, global Jackson strategy | Chosen because it *cannot* be applied inconsistently; 20 annotations give 20 chances to forget one, silently |
| D-2 | "Active unlimited" is status-only, dates ignored | Keeps the domain clock-free, so the concurrency tests stay deterministic. The expiry gap is declared, not half-built |
| D-3 | RFC 9457 `ProblemDetail` + a `code` extension | Standards-based, ships with Boot 4, near-zero custom code. `code` restores the machine-readable token `ProblemDetail` lacks |
| D-4 | Member key is trimmed and case-folded | Without it `"alice"` bypasses the rule that binds `"Alice"`. `Locale.ROOT` because a Turkish default locale lowercases `ALICE` to a dotless `alıce` |
| D-5 | No uniqueness beyond the unlimited rule | Members may hold many packs, and packs alongside one unlimited. Only FR-5(d) constrains |
| D-6 | Split `402` decline from `502` outage | They mean opposite things to a client: new card versus retry. Required extending the port |
| D-7 | Purchase stores a plan **snapshot** | A purchase is a historical record; a later price change must not rewrite what someone was charged |
| D-8 | Reject `start_date` before today | Backdating is a billing decision, not a purchase one. Cost: a clock re-enters validation |
| D-9 | A cancelled unlimited frees the member immediately | The rule reads "active", and `CANCELLED` is not active |
| D-10 | Double cancel is `409`, not a silent `200` | A client cancelling twice has a bug; hiding it helps nobody |

## 4. The questions you will be asked

**"How do you protect the last promo slot?"**
> One line: `counter.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0`. The guard is *inside* the
> update function, so there's no window between testing and decrementing — the counter is
> structurally incapable of going negative. `getAndUpdate` rather than `updateAndGet` because
> the *previous* value is what tells you whether you won; `updateAndGet` returning 0 can't
> distinguish "I took the last slot" from "there were none".

**"Why not just put a lock around the whole purchase?"**
> It's simpler and obviously correct, and it holds a lock across a network call. Throughput
> collapses to one purchase per gateway round-trip, and a *hanging* gateway makes the plan
> unpurchasable for everyone rather than slow for one person. I'd rather defend two lines of
> CAS than a liveness failure.

**"Where do the business rules live, and why there?"**
> Domain objects answer questions, the service makes decisions, stores make guarantees.
> `plan.isActive()` is a fact on the domain object; "an inactive plan cannot be purchased" is
> a decision in the service. Keeping all six rules in one service method means one file
> answers "what are the purchase rules?" — the rich-domain alternative scatters them so no
> single file does.

**"What happens if the payment fails?"**
> Three pieces of state get restored, not two: no purchase persists, the inventory slot is
> released, **and the member's unlimited claim is released.** That third one is the
> interesting part — I nearly shipped tests that missed it.

**"What if the process dies after charging but before saving?"**
> That window exists and I can't close it in-process. And it's worse than lost state:
> storage is in-memory, so **the gateway is the only durable record in the system**. A crash
> there destroys the only local evidence the charge happened — there's nothing to reconcile
> against. Real fix is an idempotency key plus a reconciliation job, or an outbox. Cheap
> partial mitigation: log the transaction id at INFO the moment the charge returns.

**"Did you guard the one-unlimited rule for concurrency?"** *(volunteer this before they ask)*
> Yes — `putIfAbsent` on a member-keyed map, which returns null exactly once. The brief only
> mentions concurrency for the promo slots, but "check for an existing unlimited, then
> insert" is the identical check-then-act race. Treating both as one pattern is why it didn't
> get forgotten.

**"How do you know your concurrency test actually works?"**
> I broke it on purpose. I replaced the CAS with a naive read-then-write and watched the
> oversell test report more than five successes, then reverted. A passing concurrency test is
> weak evidence — it proves no interleaving was hit that breaks the invariant, not that none
> exists. Breaking it is the only way to know the test has teeth.

**"Which tests did you choose, and why those?"**
> Risk-ordered, not coverage-ordered. The spine is oversell under load, payment-failure
> cleanup, the duplicate-unlimited race, and `remaining_slots: null` meaning unlimited rather
> than zero — that last one is a one-character bug that would make three of four plans
> unpurchasable. T-1 asserts four invariants including **the number of cards charged**,
> because charging all 500 and persisting five satisfies the other three.

**"Why didn't you add the validation starter?"**
> Three fields and two semantic checks don't justify `hibernate-validator` and `jakarta.el`
> in a build deliberately kept at three dependencies — and my error contract batches shape
> failures but fails fast on semantics with distinct codes, which doesn't map cleanly onto
> bean validation anyway. That split falls out of the layer boundary: the web layer sees all
> three fields so it collects; the service evaluates in order so it throws.

**"You changed our interface. Why did you feel entitled to?"**
> Your javadoc on `charge` already said *"when the charge is declined **or** the gateway
> errors"* — two distinct conditions under one type. I didn't invent the distinction, I made
> the documented contract expressible. Two added subclasses, nothing modified, and the
> signature and javadoc are byte-identical so the diff is purely additive.

**"What changes with a real database?"**
> Every port operation has a direct SQL analogue — `tryReserve` becomes
> `UPDATE … WHERE remaining_slots > 0` checking rows-affected, the unlimited claim becomes a
> unique constraint, cancel becomes a conditional `UPDATE`. The service says *what* must be
> atomic and never *how*. And one obligation disappears: the claim index is denormalised
> state I have to keep in step today, which a unique constraint on the table does for free.

**"Two instances behind a load balancer?"**
> It oversells. Correctness here depends on single-process ownership of the store, which is
> exactly what moving to a database fixes — and the port shape doesn't change.

**"Any security concerns?"**
> One that matters: `member_name` is the whole identity and the endpoint is unauthenticated,
> so I can claim the unlimited slot for a name I don't own and the real owner can *never* buy
> one — releasing it requires cancelling a purchase they can't see. There's an uncomfortable
> symmetry: the case-folding that closes the `"alice"` bypass widens that attack from one
> string to a whole equivalence class. Right decision, with a cost.

## 5. Volunteer these unprompted

Ranked by signal. Each shows something the brief didn't ask for.

1. **The one-unlimited rule has the same race as the promo counter** — the brief doesn't
   mention it, and guarding it unprompted is the strongest single signal available.
2. **The mutation test** — "I broke the CAS and watched it oversell."
3. **`remaining_slots: null` means unlimited, not zero**, and it's prevented *structurally*:
   unlimited-inventory plans have no counter at all, so there's no comparison to get
   backwards.
4. **Your example payload now returns `400`.** The README's `start_date: "2026-09-01"` is in
   the past, so rejecting past dates rejects it. Say this before they try it.
5. **`PaymentGatewayUnavailableException` currently has no producer** — the stub always
   succeeds and there's no timeout to raise it. The contract is right; nothing exercises it
   in production code.
6. **Launch Promo Pack has `credits = 5` and `remaining_slots = 5`** — identical values for
   orthogonal concepts, in the one plan every test hammers. They go into different structures
   at the seeding boundary, so confusing them stops compiling.

## 6. Three things not to say

| Don't say | Say instead |
|---|---|
| "The concurrency test proves it's correct." | "It proves no interleaving was hit that breaks the invariant. That's why I also broke the CAS to confirm the test fails." |
| "It's thread-safe, I used `ConcurrentHashMap`." | "The inventory map is *immutable* — its key set is fixed at seeding and only the `AtomicInteger` values mutate. The claim store needs a concurrent map because its keys come and go." |
| "Swapping in a database would be easy." | "The port shape maps directly to SQL, and two things don't come for free: the denormalised claim index becomes a unique constraint, but the charged-but-not-persisted window stays open until there's an idempotency key." |

If asked about AI assistance: be specific. It did design interrogation and decision logging;
the trade-offs are chosen and defensible. The evidence that it's owned is that every decision
has a recorded reason — which is what this conversation tests.

## 7. "Change it live" — the four likely asks

| Ask | Where it lands | The trap |
|---|---|---|
| **Add cancel** | `compareAndSetStatus(id, ACTIVE, CANCELLED)`, then release the claim and restore the slot — only for the thread that wins the CAS | Two concurrent cancels both read `ACTIVE`, both restore, and a 5-slot pack ends with 6. The *restore* path has the same race as the decrement |
| **Add an `Idempotency-Key`** | A third claim via `putIfAbsent`, pushed onto the same compensation stack | It **breaks** the stack's uniform rule: the dedupe key must be released on a pre-charge failure and must **not** be released post-charge, or a retry double-charges. It changes the compensation model rather than extending it |
| **Make every plan finite** | Seed an inventory entry per plan. **No code change** — `tryReserve` already branches on entry presence | Nothing. This is the one that's free, and saying so demonstrates the design |
| **Add expiry to unlimited memberships** | Reverses D-2: the rule now needs a clock | Every concurrency test becomes time-sensitive, because the clock enters the *rules* rather than staying in validation. Today only two classes hold a `Clock` and neither exposes `now()` |

## 8. Known gaps — in the order to say them

Lead with these. Volunteering them changes "did you notice?" into "what would you do?".

1. **No authentication** → name squatting (§4, last question). Only auth fixes it.
2. **No gateway timeout** → 200 hanging charges exhaust the servlet pool and take
   `GET /v1/plans` down with it. Needs a timeout mapped to the `502` type, and a bulkhead so
   payments can't starve reads.
3. **Charged-but-not-persisted window** → needs an idempotency key, an outbox, or
   reconciliation.
4. **Unbounded growth** — only the promo pack's five slots bound sales anywhere in the system;
   purchases and claims accumulate for the process lifetime.
5. **Unlimited memberships never expire** (D-2) and **pack credits are never spent** — both
   deliberate scope cuts, both revenue-relevant if shipped.
6. **In-process concurrency only** — a second instance oversells.

## 9. One sentence to close on

> "The decisions I'd defend hardest are reserve-before-charge, because it removes a whole
> defect class rather than mitigating one; and treating the two races as one pattern, because
> that's what stopped me forgetting the one the brief doesn't mention."

```
==================================================
CURRENT PHASE: Phase 5 [Interview Drill & Dry-Run Readiness]
LAST APPROVED: Phase 4 report; V-2/V-5/V-6/V-10 amendments applied
NEXT STEP: Review this drill. All five phases are then complete and the
           blueprint is ready to execute.
==================================================
```
