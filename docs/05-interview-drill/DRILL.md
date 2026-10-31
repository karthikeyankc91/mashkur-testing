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
| Contenders in the race tests | **500 virtual threads** on 5 slots, one start gate |
| Tests | **144, all green** from `clean test`; the spine is oversell, payment-failure cleanup, the duplicate-unlimited race, and `null`-means-unlimited |
| Error table | **13 rows**, one per code, in one `@RestControllerAdvice` |
| Decisions logged | 10, each with its reason |
| Vendor files touched | **2**, both additive: `application.yml` (+7), `PaymentGateway.java` (+25, two nested subclasses). **0 deletions** across both |
| Vendor files byte-identical | 6, incl. `SeedData` and `AlwaysSucceedingPaymentGateway` |
| Dependencies added | **0** — still the original three |
| Scope delivered | core + machine-readable codes + cancel + `Idempotency-Key` |
| Stack | Java 21 target, Spring Boot 4.0.0, Gradle 9.5.1, Jackson 3, JUnit 6 |

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

**"How do you know your concurrency test actually works?"** *(the strongest answer you have)*
> I broke it on purpose, three ways, and measured how often each was caught rather than
> running it once.
>
> **The uncomfortable result first: my headline oversell test is weak.** Replacing the CAS
> with a naive read-then-write, that test caught it in only **3 of 16 runs** — under 20% —
> because only 5 of 500 attempts ever reach the race window. What has teeth is the
> payment-failure test: every charge fails and returns its slot, so the five slots recycle
> about 490 times per run, and it caught the same mutant **5 of 5**. The counter finished at
> 6, 9, 10, 12, even 20 — *above* its seeded maximum, because lost updates let releases
> outnumber reservations.
>
> **That result justified a decision that had looked lazy.** `release()` deliberately doesn't
> clamp at the seeded maximum. A clamp would have pinned every one of those back to 5 and
> *inverted* the outcome — the mutant would have survived 5 of 5 instead of dying 5 of 5.
>
> It also changed the code. The one-unlimited race was caught in only 9 of 12 runs, so that
> test now carries `@RepeatedTest(10)` — re-measured at **12 of 12**, with 2 to 8 of the ten
> repetitions failing per run, which puts a single attempt near a coin flip. And I know that
> lever wouldn't have helped the oversell test, because its weakness is structural rather
> than statistical: ten repetitions take ~19% to roughly 88%, not to certainty. It needed a
> different test, not more runs of the same one.
>
> A passing concurrency test is still weak evidence — it shows no breaking interleaving was
> *hit*, not that none exists. The real answer is jcstress or Lincheck, which explore the
> state space instead of sampling it.

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
2. **The mutation testing, with numbers** — and specifically that it showed my *headline*
   test was the weak one. Measuring beats asserting, and admitting which of your own tests
   is weak is a stronger signal than claiming they all work.
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
7. **A plan assumption of mine was wrong and a test caught it.** I justified `strip()` over
   `trim()` on the grounds it handles a pasted non-breaking space. It doesn't —
   `Character.isWhitespace('\u00A0')` is `false`, because Java excludes non-breaking spaces
   by design. So `"Alice"` and `"Alice\u00A0"` would have been **different members**, making
   the one-unlimited rule bypassable with an invisible character. The key now folds on
   `isWhitespace || isSpaceChar`. Worth volunteering: it shows the tests are load-bearing,
   not decorative.

## 6. Three things not to say

| Don't say | Say instead |
|---|---|
| "The concurrency test proves it's correct." | "It proves no interleaving was hit that breaks the invariant. That's why I also broke the CAS to confirm the test fails." |
| "It's thread-safe, I used `ConcurrentHashMap`." | "The inventory map is *immutable* — its key set is fixed at seeding and only the `AtomicInteger` values mutate. The claim store needs a concurrent map because its keys come and go." |
| "Swapping in a database would be easy." | "The port shape maps directly to SQL, and two things don't come for free: the denormalised claim index becomes a unique constraint, but the charged-but-not-persisted window stays open until there's an idempotency key." |

If asked about AI assistance: be specific. It did design interrogation and decision logging;
the trade-offs are chosen and defensible. The evidence that it's owned is that every decision
has a recorded reason — which is what this conversation tests.

## 7. "Change it live"

**Cancel and `Idempotency-Key` are already built** — so if either is asked for, the answer is
a walkthrough, not a change. Both landed after core was green and merged, which the git
history shows at a glance.

| Ask | Status | What to say |
|---|---|---|
| **Add cancel** | **built** | `compareAndSetStatus(id, ACTIVE, CANCELLED)` **first**, then restore — only the thread that wins the CAS compensates. Restore-first lets two concurrent cancels both read `ACTIVE`, both restore, and a 5-slot pack end with 6: overselling through the *cancel* endpoint. I mutated it to prove that: reordering fails the cancel tests 5 of 5, with 2 to 6 callers each "successfully" cancelling one purchase |
| **Add an `Idempotency-Key`** | **built** | The key claim is the same claim-or-fail pattern, so it became compensation entry #0 on the existing stack and reused the unwind unchanged. Replay returns the original 201 plus `Idempotency-Replayed: true`; a key reused with a different payload is `409`, caught by a SHA-256 fingerprint rather than by storing the payload again. **See the honest caveat below** |
| **Make every plan finite** | free | Seed an inventory entry per plan. **No code change** — `tryReserve` already branches on entry presence. This is the one that costs nothing, and saying so demonstrates the design |
| **Add expiry to unlimited memberships** | reverses D-2 | The rule now needs a clock. Every concurrency test becomes time-sensitive, because the clock enters the *rules* rather than staying in validation. Today two classes hold a `Clock` and neither exposes `now()` |

### The idempotency caveat — volunteer this, don't wait to be caught

My own design notes predicted the trap and **the implementation walked into it anyway.**

The flow is: claim the key → charge → save → mark the key complete. If `save()` throws
*after* a successful charge, the unwind releases the key — so a retry with that key charges a
second time. In this codebase `save()` is a `ConcurrentHashMap.put` and effectively cannot
fail, so the window is unreachable today. **It becomes live the moment storage is a
database**, which is exactly the migration I claim is straightforward.

The fix is to stop treating the key like the other two claims: pop its compensation once the
charge returns, so a post-charge failure leaves the key claimed. That trades "retry is
blocked" for "retry double-charges", which is the right trade for money. The real answer is
that the key should record *"charge attempted, outcome unknown"* and a retry should
reconcile rather than re-charge — which is the same outbox/reconciliation answer as the
charged-but-not-persisted gap, because it is the same gap wearing a different hat.

Saying this before they find it is worth more than having fixed it quietly.

## 8. Known gaps — in the order to say them

Lead with these. Volunteering them changes "did you notice?" into "what would you do?".

1. **No authentication** → name squatting (§4, last question). Only auth fixes it.
2. **No gateway timeout** → 200 hanging charges exhaust the servlet pool and take
   `GET /v1/plans` down with it. Needs a timeout mapped to the `502` type, and a bulkhead so
   payments can't starve reads.
3. **Charged-but-not-persisted window** → needs an idempotency key, an outbox, or
   reconciliation.
4. **Unbounded growth** — only the promo pack's five slots bound sales anywhere in the system;
   purchases, claims **and idempotency keys** accumulate for the process lifetime. Keys are
   the worst of the three because the key is caller-supplied; a real one needs a TTL (Stripe
   uses 24 hours).
5. **The post-charge idempotency window** (§7) — a retry could double-charge if persistence
   failed after the charge. Unreachable in-memory, live against a database.
6. **Unlimited memberships never expire** (D-2) and **pack credits are never spent** — both
   deliberate scope cuts, both revenue-relevant if shipped.
7. **In-process concurrency only** — a second instance oversells.
8. **Member-name normalisation stops at the edges** — leading and trailing invisible space is
   folded, interior spacing and homoglyphs are not. Fixing it properly needs a member
   registry, not more string handling.

## 9. One sentence to close on

> "The decisions I'd defend hardest are reserve-before-charge, because it removes a whole
> defect class rather than mitigating one; and treating the two races as one pattern, because
> that's what stopped me forgetting the one the brief doesn't mention."

```
==================================================
CURRENT PHASE: Drill refreshed against the built solution
LAST APPROVED: Execution complete — 144 tests green, all four tiers
NEXT STEP: Dry run. Answer §4 out loud without reading the answers.
==================================================
```
