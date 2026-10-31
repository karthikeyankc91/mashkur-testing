# Submission notes

## Time spent

Approximately 3 hours on the implementation, preceded by a design pass that is
committed separately under `docs/`. The build is green at every commit.

## Assumptions

- **"Active unlimited" means status only.** A membership is active if its status
  is `ACTIVE`; no expiry window is evaluated, so unlimited memberships never
  lapse. Chosen because the alternative needs a term length the seed data does
  not carry, and it keeps the clock out of every business rule.
- **`member_name` is the member identity**, matched on a trimmed, case-folded
  key. There is no member registry, so `"Alice"` and `" ALICE "` are one person
  and two real people named Alice are indistinguishable.
- **Pack credits are recorded but never spent.** Consuming them is out of scope.
- **The plan is snapshotted onto the purchase**, so a later price change cannot
  rewrite what a member was charged.
- **`remaining_slots: null` means unlimited inventory, not zero.** Internally
  such plans have no counter at all.
- **Timezones are out of scope.** `start_date` is a calendar date; `created_at`
  is an instant from an injected clock.
- **Single process.** Concurrency safety is in-process only; a second instance
  would need the real store.
- **No authentication**, which is the root of the main security gap below.

> **The README's example payload now returns `400`.** Its `start_date` is
> `2026-09-01`, which is in the past, and past start dates are rejected. Use a
> future date. Flagging it so a reviewer who tries the documented example finds
> this acknowledged rather than discovers a surprise.

## API / design choices

- **`snake_case` throughout**, applied as one global Jackson naming strategy so
  it cannot be applied inconsistently. The cost is that the wire contract is
  invisible at the DTOs, so a test asserts the wire names — including the
  negative case, since camelCase would otherwise serialise perfectly happily.
- **RFC 9457 `ProblemDetail` plus a `code` extension.** `code` is the stable
  machine-readable contract; `type` is derived from it, so the two cannot
  disagree.
- **All three rule violations are `409`** — the request is well-formed but
  conflicts with current state. They are distinguished by `code`, not by status.
- **`400` vs `404` on a purchase id:** `not-a-uuid` is `400` (a malformed
  question), a well-formed unknown UUID is `404` (a sensible question, negative
  answer).
- **`402` for a declined charge, `502` for a gateway outage.** The port's own
  javadoc already said "declined **or** the gateway errors"; two subtypes of the
  existing `PaymentFailedException` make that expressible without breaking any
  caller that catches the umbrella type. This is the only vendor file changed,
  in its own commit: 25 insertions, 0 deletions.
- **Rules live in the service.** Controllers do shape validation and nothing
  else; stores own storage and the atomic primitives. Replace the web layer with
  a CLI and not one rule moves.
- **Ordering is reserve → charge → commit**, with a compensation stack. Claims
  are cheap and reversible, a charge is neither. Charging first and then finding
  the pack sold out would mean taking money for something undeliverable — a
  defect, not a trade-off.
- **No `@Service`/`@Component` outside `config/` and `web/`.** The rule is that
  only those two packages may import Spring, which makes the layering boundary a
  single greppable command. The service is constructible by hand, and every
  service test does exactly that.
- **No `PlanService`.** Listing plans enforces no rule, and a pass-through layer
  added for symmetry is cost without benefit.
- **The clock is reachable only through two collaborators that expose no
  `now()`.** Two classes hold a `Clock`; zero business rules can read one.

## Tests

110 tests. Ordered by risk, not by layer.

1. **Promo inventory under 500 concurrent purchases** — exactly 5 succeed, 495
   get `409 plan_sold_out`, the counter settles at 0, and **5 cards are charged,
   not 500**. That last assertion is the one that catches an implementation
   which charges everyone and persists five.
2. **One member, 500 simultaneous unlimited purchases** — exactly one succeeds.
   The brief only mentions concurrency for promo slots, but this rule has the
   identical check-then-act race.
3. **500 *different* members buying unlimited** — all 500 succeed. This is what
   distinguishes "correct" from "serialised": one global lock would pass test 2
   while making the endpoint useless.
4. **Payment failure, parameterised over decline / outage / umbrella type** — no
   purchase persisted, inventory restored, and the unlimited claim released.
   Parameterising is deliberate: the claim-release assertion is the one likely
   to be written for only one failure mode, and unlimited plans have no
   inventory counter to notice the leak.
5. **`remaining_slots == null` is unlimited** — guards the one-character bug of
   writing the check as `remaining > 0`, which would refuse every plan without
   an inventory limit.
6. Error contract, every row, including that a membership conflict does not echo
   the member name.

**I checked the concurrency tests can actually fail.** Replacing the CAS in
`tryReserve` with a read-then-write: the headline oversell test caught it in
only **2 of 8 runs**, while the full suite caught it in **5 of 5** — the
failure-path test recycles the 5 slots ~490 times per run, giving roughly a
hundredfold more chances to lose an update. Under that mutant the counter
finished at **11**, above its seeded maximum.

That result justified a decision that had looked like laziness: `release()`
deliberately does not clamp at the seeded maximum. A clamp would have pinned 11
back to 5 and hidden the broken CAS entirely. Replacing `putIfAbsent` with
`containsKey`-then-`put` died immediately — two memberships for one member.

A passing concurrency test is still weak evidence: it shows no breaking
interleaving was *hit*, not that none exists.

## Known gaps / what I would do next

1. **Name squatting (needs auth).** Anyone can claim the unlimited slot for any
   name, and the denial is effectively permanent because releasing it requires
   cancelling a purchase the victim cannot see. No validation fixes this —
   `member_name` is being used as an identity without proof. Authentication, and
   a real member id, is the fix.
2. **No gateway timeout.** `charge` can block indefinitely; a couple of hundred
   hanging requests would exhaust the servlet pool and take `GET /v1/plans` down
   with it. `PaymentGatewayUnavailableException` exists for exactly this and
   currently has no producer — a bounded timeout would give it one.
3. **Charged with no record.** In-memory storage makes the gateway the only
   durable record, so a crash between charging and saving destroys the only
   local evidence. Needs persistence plus reconciliation against the gateway.
4. **Request body size is unbounded.** `member_name` is capped at 100
   characters, but the body itself is not; a `OncePerRequestFilter` with a
   content-length limit is the one-line fix.
5. **Member-name normalisation stops at the edges.** Leading and trailing
   invisible space is folded (including non-breaking space — see below), but
   interior spacing, zero-width characters and homoglyphs are not. Properly
   fixing this means a member registry, not more string handling.
6. **Cancel and `Idempotency-Key` are implemented on a separate branch** and are
   not part of the core submission.

## AI tools used

Claude Code, used as a collaborator throughout: design discussion first, then
implementation against written step plans, with the build and tests run at every
step. Two things it got wrong that the build caught, both recorded in the
commits:

- The plan justified `strip()` over `trim()` with a non-breaking-space example.
  That is **wrong** — Java's `Character.isWhitespace` deliberately excludes
  `U+00A0`, so `strip()` leaves it exactly where `trim()` does. `"Alice"` and
  `"Alice "` would have been different members, making the one-unlimited
  rule bypassable with an invisible character. `MemberKey` now folds on
  `isWhitespace || isSpaceChar`.
- I asserted that only 5 charges could occur under a declining gateway with 5
  slots. Wrong: compensation returns each slot immediately, so ~490 contenders
  each acquire one in turn. The assertion was the bug, not the code.

I can walk through any of this and change it live.
