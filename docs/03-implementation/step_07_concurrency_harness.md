# Step 07 — The concurrency harness

## 1. Context & Objective

T-1 and T-3: the two races, under 500 concurrent contenders. "Concurrency —
especially the last promo slots" is a named evaluation criterion, and this step
is the whole of the evidence for it.

This is an **integration test of the atomicity story**, not a unit test of the
service: it drives `PurchaseService` with the **real** in-memory stores (step 02)
and a `CountingGateway` (step 04), because the atomicity under test lives in the
stores and mocking them would mock away the system under test (TRD §5.1).

**Objective:** the promo pack sells exactly five times out of 500 attempts, one
member gets exactly one unlimited membership out of 500 attempts, and the
harness is **proven able to detect a broken implementation**.

**Traces to:** TRD §5.3 (harness design), §5.1 (level by risk), §1.4.4
(mechanism), §4.4 (same-member race). PRD NFR-1, NFR-3, T-1, T-3, F-5.

> ⚠️ **One finding raised** — F-8. Nothing currently tests the compensation stack
> *under concurrency*. See §7.

## 2. Dependencies

| Needs | From |
|---|---|
| Real adapters with both atomic primitives | step 02 |
| `CountingGateway` with its `AtomicInteger` | step 04 |
| `PurchaseService`, the compensation stack, `TestStores` | step 05 |

**Blocks:** nothing. Step 06 is not on this path, which is the payoff of having
put the atomicity below the web layer.

## 3. Files

```
src/test/java/com/glofox/memberships/
├── Concurrency.java                             CREATE  (reusable harness)
└── service/
    └── PurchaseServiceConcurrencyTest.java      CREATE  (T-1, T-3, T-3b, T-3c, T-13)
```

No production code changes. If this step requires any, something is wrong in
steps 02 or 05.

## 4. Step-by-step

### 4.1 `Concurrency` — the harness

```java
public final class Concurrency {

    public record Outcome<T>(T value, Throwable failure) {
        boolean succeeded() { return failure == null; }
    }

    public static <T> List<Outcome<T>> raceAll(int attempts, Callable<T> task) {
        var startGate = new CountDownLatch(1);
        var results   = new ConcurrentLinkedQueue<Outcome<T>>();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    startGate.await();                     // all released together
                    try   { results.add(new Outcome<>(task.call(), null)); }
                    catch (Throwable t) { results.add(new Outcome<>(null, t)); }
                    return null;
                });
            }
            startGate.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, SECONDS))
                .as("all %d attempts finished", attempts).isTrue();
        }
        return List.copyOf(results);
    }
}
```

Four decisions, and the first is the one that matters most:

1. **Virtual threads are not an optimisation here — they are a correctness
   requirement of the pattern.** With a *bounded platform* pool, 500 tasks each
   blocking on `startGate.await()` would occupy every worker thread, so the
   remaining tasks would never start, so the gate would never be reached by all
   of them — and with `countDown()` after submission the test would simply run a
   trickle of tasks serially, quietly destroying the contention it exists to
   create. Virtual threads unmount while blocked, so the barrier pattern is safe
   at any `N`. **The classic way this test silently stops testing anything is a
   `newFixedThreadPool(8)`.**
2. **Explicit `shutdown()` + `awaitTermination(30, SECONDS)` with an
   assertion**, rather than try-with-resources. `ExecutorService.close()` waits
   indefinitely, so a deadlock in the code under test becomes a hung build with
   no message. This way it becomes a failed assertion that names the problem.
3. **`Throwable`, not `Exception`.** An `AssertionError` or `StackOverflowError`
   inside a task must be captured, not swallowed into a lost result that makes
   the counts silently wrong.
4. **`ConcurrentLinkedQueue`, then `List.copyOf`.** Collecting into an
   `ArrayList` from 500 threads loses entries without ever throwing — the
   failure mode is a count that is quietly too low, which in T-1 looks like
   *passing*.

### 4.2 T-1 — the promo pack never oversells

```java
@Test
void promoPackSellsExactlyFiveTimesUnder500Contenders() {
    var gateway = CountingGateway.succeeding();
    var stores  = TestStores.fresh();
    var service = TestStores.serviceWith(stores, gateway);
    var tomorrow = LocalDate.now().plusDays(1);

    var outcomes = Concurrency.raceAll(500, () ->
        service.purchase("member-" + UUID.randomUUID(), PROMO_PACK_ID, tomorrow));

    var successes = outcomes.stream().filter(Outcome::succeeded).toList();
    var failures  = outcomes.stream().filter(o -> !o.succeeded()).toList();

    assertThat(successes).as("exactly the advertised five").hasSize(5);
    assertThat(failures).hasSize(495)
        .allSatisfy(o -> assertThat(o.failure()).isInstanceOf(PlanSoldOutException.class));
    assertThat(stores.inventory().remaining(PROMO_PACK_ID))
        .as("counter settles at zero, never negative").hasValue(0);
    assertThat(gateway.charges()).as("five cards charged, not 500").isEqualTo(5);
    assertThat(gateway.amounts()).containsOnly(2500);
}
```

**All four invariants, and each catches a different wrong implementation** —
this is F-5 in full:

| Assertion | The bug it catches |
|---|---|
| exactly 5 successes | overselling |
| all 495 failures are `PlanSoldOutException` | the losers getting a `500`, or the wrong rule rejecting them |
| final counter is `0` | a counter that went negative, or never moved |
| **`charges() == 5`** | **charging all 500 cards and persisting five** — which satisfies the other three |
| amounts are all `2500` | charging the wrong plan's price |

**Distinct member names per attempt**, deliberately. Promo Pack is a `PACK`, so
the unlimited claim is not involved — distinct names keep T-1 contending on
*inventory only*, so a failure localises to one primitive instead of two.

### 4.3 T-3 — one member, one unlimited membership

```java
@Test
void sameMemberWinsExactlyOneUnlimitedMembership() {
    var gateway = CountingGateway.succeeding();
    var stores  = TestStores.fresh();
    var service = TestStores.serviceWith(stores, gateway);

    var outcomes = Concurrency.raceAll(500, () ->
        service.purchase("Alice", MONTHLY_UNLIMITED_ID, LocalDate.now().plusDays(1)));

    assertThat(successes(outcomes)).hasSize(1);
    assertThat(failures(outcomes)).allSatisfy(o ->
        assertThat(o.failure()).isInstanceOf(UnlimitedMembershipExistsException.class));
    assertThat(gateway.charges()).as("one card charged, not 500").isEqualTo(1);
}
```

**This is the test for the race the brief never mentions** (NFR-3). `charges()
== 1` is T-3's version of F-5: without it, an implementation that charged 500
cards and kept one membership would pass.

Two companions, both cheap and both defending a specific decision:

```java
@Test
void caseAndWhitespaceVariantsStillYieldOneMembership() {
    // 500 attempts cycling through "Alice", "alice", "ALICE", " Alice ", " alice"
    // -> exactly 1 success.
}
```

> Proves D-4's normalisation holds **under contention**, not just in
> `MemberKeyTest`. Step 02 established that `MemberKey.equals` plus
> `putIfAbsent` *are* the rule; this is that claim tested where it matters.

```java
@Test
void differentMembersDoNotContend() {
    // 500 DISTINCT members, all buying Monthly Unlimited -> 500 successes,
    // charges() == 500.
}
```

> Proves the claim is keyed per member, not globally (§4.4). A global lock would
> still pass T-3 while making the endpoint useless — this is the test that
> distinguishes "correct" from "serialised".

### 4.4 T-13 — the compensation stack under load (F-8)

```java
@Test
void failedChargesUnderLoadLeakNoInventory() {
    var gateway = CountingGateway.declining();
    var stores  = TestStores.fresh();
    var service = TestStores.serviceWith(stores, gateway);

    var outcomes = Concurrency.raceAll(500, () ->
        service.purchase("member-" + UUID.randomUUID(), PROMO_PACK_ID,
                         LocalDate.now().plusDays(1)));

    assertThat(successes(outcomes)).isEmpty();
    assertThat(stores.inventory().remaining(PROMO_PACK_ID))
        .as("every reserved slot was returned").hasValue(5);
    assertThat(stores.purchases().findById(any)).isEmpty();   // nothing persisted
}
```

See §7. T-2 proves the compensation works once; this proves 500 concurrent
reserve-then-release cycles leave the counter exactly where it started.

**Optional hardening, if time allows** — a gateway that fails about half the
time, asserting the property `successes + remaining == 5`. That single invariant
holds for *every* interleaving and every failure ratio, which makes it a stronger
statement than any fixed count. Named here because it is the test to add first if
this area is ever revisited.

### 4.5 Flakiness discipline

- **Every assertion is on an invariant, not an observed schedule** (§5.3), so
  even a fully serialised run still verifies correctness.
- **Fresh stores per test** via `TestStores.fresh()` — no test inherits another's
  inventory.
- **No `Thread.sleep` anywhere.** A start gate releases all tasks; termination is
  awaited with a bounded assertion.
- **No clock control needed.** T-1, T-3 and T-13 take `Clock.systemUTC()` and a
  `start_date` of tomorrow. This is §5.4's payoff being collected: had the
  active-unlimited rule been date-windowed (the D-2 alternative), every test here
  would need a controlled clock, turning the hardest tests in the suite into the
  most fragile.
- `@RepeatedTest(10)` is the hardening lever if the suite ever goes quiet; 500
  contenders once is the baseline.

**Said plainly, because it is the honest position:** a *passing* concurrency test
is weak evidence. It proves no interleaving was hit that breaks the invariant,
not that none exists. Which is why §5 below does not stop at "the tests pass".

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. The headline tests
./gradlew test --tests '*PurchaseServiceConcurrencyTest' --console=plain

# 2. Repeatability — ten consecutive clean runs, no flakes
for i in $(seq 1 10); do
  ./gradlew test --tests '*PurchaseServiceConcurrencyTest' --console=plain -q --rerun-tasks \
    > /tmp/conc-$i.log 2>&1 && echo "run $i OK" || { echo "run $i FAILED"; tail -30 /tmp/conc-$i.log; }
done

# 3. Full suite still green
./gradlew test --console=plain

# 4. No production code was touched by this step
git diff --stat HEAD -- src/main
# expect: empty output

# 5. The harness is not accidentally serialised (the §4.1 trap)
grep -n "newFixedThreadPool\|newSingleThreadExecutor\|Thread.sleep" \
     src/test/java/com/glofox/memberships/Concurrency.java \
  && echo "FAIL: the harness cannot create real contention" \
  || echo "OK: virtual threads, no sleeps"
```

### 6. The step that gives the harness teeth

**Do this once, by hand, and keep the result.** It takes two minutes and it is
the only way to know the tests can fail.

```bash
# Break the CAS in InMemoryInventoryStore — replace the atomic update with the
# naive read-then-write the whole design exists to avoid:
#
#     int current = counter.get();
#     if (current <= 0) return false;
#     counter.set(current - 1);          // <-- the bug
#
./gradlew test --tests '*PurchaseServiceConcurrencyTest.promoPackSellsExactlyFiveTimesUnder500Contenders'
# EXPECT: FAILURE — more than 5 successes, and/or a final counter below 0.
# Then revert.

# Same for the claim store: replace putIfAbsent with containsKey-then-put.
./gradlew test --tests '*PurchaseServiceConcurrencyTest.sameMemberWinsExactlyOneUnlimitedMembership'
# EXPECT: FAILURE — more than one success.
# Then revert.
```

**Record both observed failure messages in `SUBMISSION.md`.** "How do you know
your concurrency test works?" is a near-certain follow-up question, and
*"I broke the CAS and watched it oversell 23 slots"* is a far better answer than
*"it passes"*. It is hand-run mutation testing on the two lines that matter, and
it converts a green suite from an assertion into evidence.

## 6. Notes for the next step

Step 08 adds the end-to-end `@SpringBootTest` (T-6 plus one `402` proving the
bean override wires) and writes `SUBMISSION.md`. Inputs it needs from here:

- the two mutation results from §6, verbatim;
- the thread count and the four T-1 invariants, for the "Tests" section;
- F-8's existence, for "known gaps" if T-13 was not written.

## 7. Finding raised by this step

### F-8 — nothing tested the compensation stack under concurrency

T-2 and T-10 (step 05) prove cleanup works for **one** failed charge. T-1 proves
the reservation is atomic when every charge **succeeds**. Neither covers the
combination, and the combination is where a compensation bug would actually show:
500 concurrent reserve-then-release cycles on one counter.

The mechanism is correct as designed (`getAndUpdate` down, `incrementAndGet`
up), so this is coverage of an untested interaction rather than a suspected
defect — but it is five lines using a harness that already exists, and it is the
direct answer to "how do you know the compensation doesn't leak slots under
load?"

**Resolution:** added as T-13 in §4.4 above.
**Amendment needed:** PRD §11's test table gains T-13.

## 8. Mutation testing results — run, not assumed (DV-3)

Both primitives were broken by hand and the suite re-run. **The result changed
what I believe about my own tests.**

### Mutant A — naive read-then-write in `InMemoryInventoryStore`

```java
int current = counter.get();
if (current <= 0) return false;
counter.set(current - 1);     // lost update
```

| Target | Killed |
|---|---|
| `promoPackSellsExactlyFiveTimesUnder500Contenders` alone | **3 of 16 runs (~19%)** — measured as 2/8 and 1/8 in two separate samples |
| the full concurrency suite | **5 of 5 runs (100%)** |

**The headline oversell test is far weaker than it looks.** Only 5 of 500
attempts can ever win, so there are roughly five trips through the
`get()`-to-`set()` window per run — too few to hit reliably.

`failedChargesUnderLoadLeakNoInventory` is what actually has teeth. Every charge
fails and returns its slot, so the five slots recycle ~490 times in one run:
about a hundredfold more chances to lose an update. Under the mutant the counter finished
at **6, 9, 10, 11, 12 and 20** across runs — all *above* its seeded maximum of
5, because lost updates let releases outnumber reservations.

**This retrospectively justifies a decision that looked like laziness.**
`release()` deliberately does not clamp at the seeded maximum (step 02 §4.2). A
clamp would have pinned every one of those values back to 5 and **inverted the
result** — the mutant would have survived 5 of 5 instead of dying 5 of 5. The
counter's ability to hold an impossible value is what made the bug visible.

### Mutant C — cancel restores before the compare-and-set (added in step 09)

| Target | Killed |
|---|---|
| the cancel suite | **5 of 5 runs (100%)** |

`concurrentCancelsRestoreExactlyOneSlot` and its unlimited companion report
**2, 3, 4 and 6** callers each "successfully" cancelling the same purchase.
Deterministic enough to be a regression guard rather than a sampling exercise.

### Mutant B — `containsKey` then `put` in `InMemoryUnlimitedClaimStore`

```java
if (claims.containsKey(key)) return false;
claims.put(key, purchaseId);
```

| Target | Killed |
|---|---|
| the full concurrency suite, single `@Test` | **9 of 12 runs (75%)** |
| the full concurrency suite, `@RepeatedTest(10)` | **12 of 12 runs (100%)** |

Killed by `sameMemberWinsExactlyOneUnlimitedMembership` and its case/whitespace
companion, granting **2 or 3** memberships where one is allowed.

**Fixed, and the fix was measured rather than assumed.**
`sameMemberWinsExactlyOneUnlimitedMembership` now carries `@RepeatedTest(10)`.
Re-measuring the same mutant over another 12 suite runs: killed **12 of 12**,
with **2 to 8** of the ten repetitions failing per run. Those counts imply a
single-attempt detection rate of roughly 40-50%, so one `@Test` was close to a
coin flip and ten samples drops the chance of a total miss to about 0.1%. The
whole test costs ~50ms, so there was no trade-off to weigh.

**Why this lever does not help Mutant A.** `@RepeatedTest` only buys
reliability when each attempt independently samples the same race. Mutant A's
oversell test has a *structural* limit: only 5 of 500 attempts ever reach the
race window, so repeating it ten times would move ~19% to roughly 88%, not to
certainty. That one needed a different test — the failure-path test, which
recycles the slots ~490 times — rather than more runs of the same one. Worth
knowing which of the two problems you have before reaching for the annotation.

**Correction to an earlier draft of this section**, which said this mutant
"dies immediately" and far more readily than Mutant A. That was one observation,
and a larger sample disagrees: a first 5-run sample killed it only **1 of 5**,
and the 12-run sample settled at 75%. It is more detectable than Mutant A's
*targeted* test (~19%) because all 500 attempts contend on the same key rather
than only 5 winning, but it was **less** reliable than the suite's 100% against
Mutant A — which made it the weakest detection in the suite until
`@RepeatedTest(10)` closed it to 12 of 12.

### What this means for the submission

The honest claim is not "my concurrency tests pass". It is:

- the oversell test passes **and** a deliberately broken CAS is caught, though
  only by the failure-path test, with reliability I measured rather than guessed
  — all three mutants are now caught in every run, the third only after
  `@RepeatedTest(10)` was added on the strength of the measurement;
- a passing concurrency test remains weak evidence — it shows no breaking
  interleaving was *hit*, not that none exists;
- if this had to be stronger, the next step is not more assertions but a
  deterministic interleaving tool (jcstress or Lincheck), which explores the
  state space instead of sampling it.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 07
LAST APPROVED: step_06 (purchase endpoints and the error contract)
NEXT STEP: Review step_07 and decide F-8 (adds PRD T-13). On approval ->
           step_08_integration_and_submission.md, the last step.
==================================================
```
