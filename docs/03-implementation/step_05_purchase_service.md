# Step 05 — `PurchaseService`, the compensation stack, and the rules

## 1. Context & Objective

The core of the exercise. Implements TRD §1.4.2's eight-step flow, the
compensation stack with F-1's safe unwind, and all six FR-5 rules in one method
so that a single file answers "what are the purchase rules?" (§1.3).

**Objective:** every business rule is enforced and tested, and a failed charge
provably leaves no purchase, no inventory change, **and no held claim**.

**Traces to:** TRD §1.3 (rule placement), §1.4.2 (the flow and the unwind), §1.5
(validation split), §1.6 (clock boundary), §2.5 (ports), §4.2 (escape paths),
§4.4 (same-member race), §4.5 (ordering), §4.9 (cleanup parity), §5.2 (counting
stubs). PRD FR-3, FR-5, FR-6, AC-3, AC-5, AC-6, AC-7, AC-8a/8b, AC-11, T-2, T-4,
T-5, T-9, T-10.

> ⚠️ **Two findings surfaced while writing this step** — F-6 and F-7 — both
> against already-approved documents. See §7. Neither has been silently
> absorbed; F-7 in particular is a bug class the current tests would miss
> entirely.

## 2. Dependencies

| Needs | From |
|---|---|
| Domain records, `MemberKey`, the four ports | step 01 |
| The four adapters, `ClockConfig`, `PlanSeedMapper` | step 02 |
| `PaymentDeclinedException`, `PaymentGatewayUnavailableException`, `CountingGateway` | step 04 |

**Blocks:** step 06 (controllers call this), step 07 (the harness drives it).

## 3. Files

```
src/main/java/com/glofox/memberships/
├── service/                                   ← framework-free, see 4.1
│   ├── PurchaseService.java                   CREATE
│   ├── StartDatePolicy.java                   CREATE
│   ├── PurchaseFactory.java                   CREATE   ← F-6
│   ├── PlanNotFoundException.java             CREATE
│   ├── PlanInactiveException.java             CREATE
│   ├── PlanSoldOutException.java              CREATE
│   ├── UnlimitedMembershipExistsException.java CREATE
│   ├── PurchaseNotFoundException.java         CREATE
│   ├── BlankMemberNameException.java          CREATE
│   └── StartDateInPastException.java          CREATE
└── config/
    ├── ServiceConfig.java                     CREATE   ← all wiring lives in config/
    └── PlanSeedMapper.java                    MODIFY   ← package-private → public (see §7, DV-1)

src/test/java/com/glofox/memberships/
├── TestStores.java                            CREATE   (shared fixture, also used by step 07)
└── service/
    ├── PurchaseServiceRulesTest.java          CREATE   (T-4, T-5, T-9, + 5 more)
    └── PurchaseServicePaymentFailureTest.java CREATE   (T-2, T-10, parameterised)
```

## 4. Step-by-step

### 4.1 One rule for the whole codebase: all wiring lives in `config/`

Step 02 established that the adapters carry no Spring annotations — they are
plain classes `SeedingConfig` constructs. Extending that to the service gives a
single, greppable rule:

> **`config/` and `web/` may import Spring. `domain/`, `store/`, `storage/memory/`
> and `service/` may not.**

So `PurchaseService` gets **no `@Service` annotation**; `ServiceConfig` declares
it. The cost is one small config class. The benefit is that §1.2's boundary
becomes one command (verification #3) instead of four with exceptions, and
`service/` stays constructible by hand — which is exactly what every test in
this step does.

*Alternative considered:* `@Service` plus component scanning, which is more
idiomatic Spring and would put one framework import in the package. Rejected for
the uniform rule; noted because "why no `@Service`?" is a fair question.

```java
@Configuration
class ServiceConfig {
    @Bean StartDatePolicy startDatePolicy(Clock clock)   { return new StartDatePolicy(clock); }
    @Bean PurchaseFactory purchaseFactory(Clock clock)   { return new PurchaseFactory(clock); }

    @Bean PurchaseService purchaseService(PlanCatalogue catalogue, InventoryStore inventory,
                                          UnlimitedClaimStore claims, PurchaseStore purchases,
                                          PaymentGateway gateway, StartDatePolicy startDates,
                                          PurchaseFactory factory) {
        return new PurchaseService(catalogue, inventory, claims, purchases,
                                   gateway, startDates, factory);
    }
}
```

`PaymentGateway` resolves to the vendor's `@Component` bean — the one
scanned component in the application, left exactly as shipped.

### 4.2 `StartDatePolicy` and `PurchaseFactory` — the clock, twice over (F-6)

TRD §1.6 put the `Clock` in exactly one class. Writing FR-6 revealed that the
service also needs a `createdAt` timestamp, which the TRD never accounted for
(F-6). The resolution keeps §1.6's *intent* — **the clock is reachable only
through operations that need it, never as a field and never as "tell me the
time"**:

```java
public record StartDatePolicy(Clock clock) {
    public void requireNotPast(LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now(clock))) {
            throw new StartDateInPastException(startDate);
        }
    }
}

public record PurchaseFactory(Clock clock) {
    public Purchase create(UUID id, String memberName, MemberKey key, Plan plan,
                           LocalDate startDate, String transactionId) {
        return new Purchase(id, memberName, key, PlanSnapshot.of(plan),
                            plan.priceCents(), PurchaseStatus.ACTIVE,
                            startDate, transactionId, clock.instant());
    }
}
```

**Neither exposes `now()` or `today()`.** `PurchaseService` holds both
collaborators and still has no way to ask what time it is, so a future expiry
rule cannot acquire a time dependency — which was §1.6's whole point. Two
classes hold a `Clock`; zero rules can read one.

**Bonus the TRD did not anticipate:** with `createdAt` coming from an injected
clock, a test can assert equality on a whole `Purchase` record. `Instant.now()`
inline would have made that impossible.

### 4.3 The seven exceptions

Plain, HTTP-free, each carrying the data step 06 needs to build a `detail`
string. No shared base class — TRD §3.6 rejected a `DomainException` holding its
own status, and a base holding *nothing* earns nothing.

```java
public final class PlanNotFoundException extends RuntimeException {
    private final UUID planId;
    public PlanNotFoundException(UUID planId) {
        super("Plan not found: " + planId);
        this.planId = planId;
    }
    public UUID planId() { return planId; }
}
```

Same shape for the rest:

| Exception | Carries | → step 06 |
|---|---|---|
| `PlanNotFoundException` | `planId` | `404 plan_not_found` |
| `PurchaseNotFoundException` | `purchaseId` | `404 purchase_not_found` |
| `PlanInactiveException` | `planId`, plan name | `409 plan_inactive` |
| `UnlimitedMembershipExistsException` | `MemberKey` | `409 unlimited_membership_exists` |
| `PlanSoldOutException` | `planId`, plan name | `409 plan_sold_out` |
| `BlankMemberNameException` | — | `400 validation_failed` |
| `StartDateInPastException` | the rejected date | `400 start_date_in_past` |

### 4.4 `PurchaseService.purchase(...)` — TRD §1.4.2 made real

**Three parameters, not a command object.** The web DTO cannot cross the
boundary (§1.1), and a `PurchaseCommand` record duplicating its three fields
would be a type with no behaviour — the same ceremony §1.1 rejected for
`PlanService`. Tier 3's idempotency key is when a command object earns its
place, because that is when there is a fourth field and a reason.

```java
public Purchase purchase(String memberName, UUID planId, LocalDate startDate) {

    // --- step 2: semantics (TRD §1.5 — the service owns rules) --------------
    requireNonBlank(memberName);
    startDates.requireNotPast(startDate);

    // --- steps 1-2: resolution, then plan state -----------------------------
    Plan plan = catalogue.find(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    if (!plan.active()) {                              // R-1: the decision is here,
        throw new PlanInactiveException(plan);         // not a throwing domain method
    }

    // --- step 3: identity + compensation stack ------------------------------
    MemberKey key      = new MemberKey(memberName);
    UUID purchaseId    = UUID.randomUUID();
    Deque<Runnable> compensations = new ArrayDeque<>();

    try {
        // --- step 4: claim the member's unlimited slot ----------------------
        if (plan.type() == PlanType.UNLIMITED) {
            if (!claims.tryClaim(key, purchaseId)) {
                throw new UnlimitedMembershipExistsException(key);
            }
            compensations.push(() -> releaseClaim(key, purchaseId));
        }

        // --- step 5: reserve finite inventory ------------------------------
        if (inventory.remaining(planId).isPresent()) {
            if (!inventory.tryReserve(planId)) {
                throw new PlanSoldOutException(plan);
            }
            compensations.push(() -> inventory.release(planId));
        }

        // --- step 6: charge. NOTHING is locked here ------------------------
        var result = gateway.charge(plan.priceCents(), memberName);

        // --- steps 7-8: persist and return ---------------------------------
        Purchase purchase = factory.create(purchaseId, memberName, key, plan,
                                           startDate, result.transactionId());
        purchases.save(purchase);
        return purchase;

    } catch (RuntimeException original) {
        unwind(compensations, original);       // F-1
        throw original;
    }
}
```

Five points to get right:

1. **`ArrayDeque` with `push`/`pop` makes "unwind in reverse" structural.** A
   `List` plus `Collections.reverse` would express the same thing as an extra
   step someone can forget. The data structure *is* the LIFO requirement.
2. **`catch (RuntimeException)`, never `catch (PaymentFailedException)`** (§4.9).
   Correct under step 04's subtype design *and* if the hierarchy were ever
   flattened. It also catches `PlanSoldOutException` and the claim conflict, so
   one path cleans up after every failure.
3. **`inventory.remaining(planId).isPresent()`** is the finite-inventory test.
   Not `plan.type() == PACK` — pack-ness and finite inventory are independent,
   and only the inventory store knows which plans have counters (§2.3).
4. **The charge uses `plan.priceCents()`** and the same value lands in
   `amountCents` via the factory, so AC-3's "amount charged" cannot drift from
   what was charged.
5. **`memberName` is passed to the gateway and stored as submitted** (D-4),
   while `MemberKey` normalises for matching. *Nit worth knowing:* D-4 says
   "store as submitted", so `" Alice "` is displayed with its spaces. Stripping
   for display would be defensible; the TRD chose as-submitted, so this follows
   it.

**The unwind, and the two helpers:**

```java
private static void unwind(Deque<Runnable> compensations, RuntimeException original) {
    while (!compensations.isEmpty()) {
        try {
            compensations.pop().run();
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);        // keep the REAL cause
            log.error("compensation failed", cleanupFailure);
        }
    }
}

private void releaseClaim(MemberKey key, UUID purchaseId) {
    if (!claims.release(key, purchaseId)) {               // TRD §1.4.3
        log.error("invariant breach: released a claim we did not own, member={} purchase={}",
                  forLog(key.value()), purchaseId);
    }
}

/** V-5: member_name is caller-controlled and must never reach a log raw. */
private static String forLog(String raw) {
    return raw == null ? "null" : raw.replaceAll("[\\r\\n\\p{Cntrl}]", "_");
}

private static void requireNonBlank(String memberName) {
    if (memberName == null || memberName.strip().isEmpty()) {
        throw new BlankMemberNameException();
    }
}
```

`addSuppressed` rather than letting the cleanup failure replace the original is
the whole of F-1: **a failure handler that can fail is not a failure handler.**

**`forLog` is V-5, and it applies to every user-derived value in a log
statement.** `member_name` is caller-controlled, so a value of
`"Alice\n2026-10-31 ERROR [c.g.m.PurchaseService] inventory reconciled to 500"`
forges a log line, and ANSI escapes in the same field can manipulate a terminal
tailing the log. Where the name adds nothing diagnostically, prefer logging the
server-generated `purchaseId` and omitting it entirely — the cheapest
sanitisation is not emitting the value.

The exception messages are the other half of this: `UnlimitedMembershipExists`
carries a `MemberKey`, and step 06's `500` branch logs the exception. Keep
caller-supplied text out of exception messages that are logged, or sanitise at
the logging boundary as above.

### 4.5 `getById`

```java
public Purchase getById(UUID id) {
    return purchases.findById(id).orElseThrow(() -> new PurchaseNotFoundException(id));
}
```

The service throws, not the controller — so every exception in the §3.6 table
originates in one layer and the controller stays free of decisions.

### 4.6 `TestStores` — shared fixture

```java
public final class TestStores {                  // src/test/java/com/glofox/memberships/
    public static PlanCatalogue catalogue() { /* SeedData.PLANS via PlanSeedMapper */ }
    public static InventoryStore inventory() { /* filter remainingSlots != null */ }
    // ...plus a record bundling all four stores + the service, for brevity
}
```

Each call returns **freshly constructed** stores, so no test inherits another's
inventory. This is why `PlanSeedMapper` becomes public (§7).

### 4.7 `PurchaseServiceRulesTest`

Plain JUnit 6 + AssertJ, real stores, `Clock.fixed`, no Spring context.

| Test | Asserts | Traces |
|---|---|---|
| `unknownPlanRejected` | `PlanNotFoundException`; **gateway never called** | FR-3 step 3 |
| `inactivePlanRejected` | Legacy Plan → `PlanInactiveException`; `charges() == 0` | **T-5**, AC-5 |
| `blankMemberNameRejected` | `""`, `"   "`, `" "` → `BlankMemberNameException` | FR-3 step 2 |
| `pastStartDateRejected` | yesterday → throws; **today and tomorrow succeed** | **T-9**, AC-11 |
| `unlimitedInventoryPlanBuysRepeatedly` | 10-Class Pack × 3 different members all succeed; `remaining` stays `empty` | **T-4** — the `remaining > 0` bug |
| `secondUnlimitedSameMemberRejected` | second buy → `UnlimitedMembershipExistsException`; also for `" ALICE "` | AC-6, D-4 |
| `packsDoNotBlockUnlimited` | same member: pack then unlimited, both succeed | D-5 |
| `promoDecrementsByExactlyOne` | `remaining` 5 → 4 after one purchase | AC-7 |
| `promoSoldOutAfterFive` | 6th → `PlanSoldOutException`; `charges() == 5`, not 6 | AC-7 |
| `purchaseSnapshotsPlanAndAmount` | `amountCents == 8000`; snapshot name/type/price match; `transactionId` non-blank | AC-3, AC-9, D-7 |

`promoSoldOutAfterFive` asserting `charges() == 5` is FR-3's ordering made
observable: the gateway must not be called for a request already doomed (§4.5).

### 4.8 `PurchaseServicePaymentFailureTest` — T-2 and T-10 as one test

```java
static Stream<Arguments> failureModes() {
    return Stream.of(
        arguments("decline", (Supplier<CountingGateway>) CountingGateway::declining,
                  PaymentDeclinedException.class),
        arguments("outage",  (Supplier<CountingGateway>) CountingGateway::unavailable,
                  PaymentGatewayUnavailableException.class));
}

@ParameterizedTest(name = "{0}")
@MethodSource("failureModes")
void failedChargeLeavesNothingBehind(String name, Supplier<CountingGateway> gw, Class<?> expected) { ... }
```

**One parameterised test, not two** (§4.9): a third failure mode cannot be added
without its cleanup assertions arriving with it.

Assertions, for **both** modes:

| Assert | Why |
|---|---|
| the expected exception type propagates | AC-8a / AC-8b |
| `gateway.charges() == 1` | the charge really was attempted — otherwise the test is vacuous |
| the purchase store is **empty** | FR-5(f): no purchase remains |
| promo `remaining` is **still 5** | FR-5(f): inventory unchanged |
| **the unlimited claim was released** — a retry for the same member succeeds | **F-7, see §7** |

Plus `suppressedCauseIsKeptWhenCleanupFails` — inject a store whose `release`
throws, assert the original payment exception still propagates and carries the
cleanup failure in `getSuppressed()`. This is the only test of F-1.

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Full suite
./gradlew test --console=plain

# 2. This step
./gradlew test --tests '*PurchaseServiceRulesTest' \
               --tests '*PurchaseServicePaymentFailureTest' --console=plain

# 3. THE UNIFORM BOUNDARY (§4.1) — only config/ and web/ may import Spring
grep -rn "org\.springframework\|jackson" \
     src/main/java/com/glofox/memberships/domain \
     src/main/java/com/glofox/memberships/store \
     src/main/java/com/glofox/memberships/storage \
     src/main/java/com/glofox/memberships/service \
  && echo "FAIL: framework leaked past config/ and web/" \
  || echo "OK: four packages are framework-free"

# 4. The clock boundary after F-6 — TWO holders, and no way to ask the time
grep -rln "java\.time\.Clock" src/main/java | sort
# expect exactly: config/ClockConfig.java, service/PurchaseFactory.java, service/StartDatePolicy.java
grep -rn "Instant\.now()\|LocalDate\.now()" src/main/java \
  && echo "FAIL: an un-injected clock read" || echo "OK: no ambient clock reads"
grep -rn "Clock " src/main/java/com/glofox/memberships/service/PurchaseService.java \
  && echo "FAIL: the service holds a Clock" || echo "OK: service cannot ask the time"

# 5. One method holds all six FR-5 rules (§1.3's claim, made checkable)
grep -c "throw new" src/main/java/com/glofox/memberships/service/PurchaseService.java   # expect >= 5

# 6. Vendor files still untouched beyond step 03/04's two
git diff --stat 32ed602 -- src/main/java/com/glofox/memberships/config/SeedData.java \
                            src/main/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGateway.java \
                            src/main/java/com/glofox/memberships/MembershipsApplication.java
# expect: empty output
```

**Done when:** all six pass. `./gradlew bootRun` still boots — the new beans
resolve, which is the first real test of `ServiceConfig`.

## 6. Notes for the next step

Step 06 adds `POST /v1/memberships/purchases`, `GET …/{id}`, and
`ApiExceptionHandler`. It maps the seven exceptions above onto the §3.6 table and
owns the shape validation half of §1.5 (`member_name` present, `plan_id` a UUID,
`start_date` parseable), batching those into the `errors` array while the
semantic failures from this step arrive one at a time with their own codes (F-2).

## 7. Findings raised by this step

Both are against approved documents. Neither is applied yet.

### F-6 — the TRD's clock boundary never accounted for `createdAt`

§1.6 declared exactly one class may hold a `Clock`, and FR-6 requires a
`createdAt` timestamp on every purchase. Those are incompatible as written: the
service needs the time and §1.6 forbids it the means.

**Resolved above** by a second single-purpose holder, `PurchaseFactory`, so the
clock stays behind operations that need it and `PurchaseService` still cannot ask
what time it is. §1.6's *intent* survives; its *letter* ("exactly one class")
does not.

**Amendments needed:** TRD §1.6 to say two holders and state the real invariant
(no class may expose the current time); **step 02's verification #4**, which
expects `grep java.time.Clock` to return exactly one file and will now fail.

### F-7 — T-2 and T-10 never assert the unlimited claim is released

PRD T-2 says "no purchase persisted **and** promo inventory unchanged". Both
hold for a failed *unlimited* purchase for a reason that makes the test
worthless: **an unlimited plan has no inventory counter at all.** So a service
that reserved the member's unlimited claim and never released it on payment
failure would pass T-2 and T-10 exactly as written — while permanently barring
that member from ever buying an unlimited membership again.

The claim is a third piece of mutable state, added after the T-list was written,
and nothing asserts its cleanup.

**Fix:** T-2 and T-10 gain an assertion that a retry for the same member
succeeds after a failed charge. Added to §4.8 above.

**Amendments needed:** PRD T-2 and T-10 wording; PRD AC-8's cleanup clause, which
says "no purchase … and `remaining_slots` unchanged" and should also say the
member's unlimited claim is released.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 05
LAST APPROVED: step_04 (payment port extension + CountingGateway)
NEXT STEP: Review step_05, and decide F-6 and F-7 (both amend approved
           documents). On approval -> step_06_purchase_endpoints_and_errors.md
==================================================
```
