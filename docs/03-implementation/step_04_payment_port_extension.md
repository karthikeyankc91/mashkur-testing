# Step 04 — Payment port extension

## 1. Context & Objective

Add two nested exception subtypes to `PaymentGateway` so that a decline and an
outage become distinguishable (Q-3 / D-6 / AC-8a / AC-8b), and create the shared
test gateway that steps 05 and 07 both depend on.

**This is the only change to vendor *code* in the entire exercise.** It is its
own step for exactly that reason: one commit, so a reviewer can confirm what was
*added* to `PaymentGateway` and that nothing was *modified*. Ten lines of
production code earn a step boundary when provenance is on the line.

**Objective:** both subtypes exist, `AlwaysSucceedingPaymentGateway` is
byte-identical, §1.7's central design claim is locked in by a test, and a
counting test gateway is available to later steps.

**Traces to:** TRD §1.7 (the decision and the rejected alternatives), §3.6
(mapping table, incl. the base-type fallback row), §4.9 (cleanup parity), §5.2
(hand-written counting stubs over Mockito mocks). PRD D-6, AC-8a, AC-8b, AS-6,
AS-9, T-2, T-10.

## 2. Dependencies

| Needs | From |
|---|---|
| Nothing in `domain/` or `store/` | — |
| `PaymentGateway` (vendor) | skeleton |

Independent of steps 01–03. It could run first; it sits here because steps 01–03
deliver a runnable endpoint sooner and this is only needed by step 05.

**Blocks:** step 05 (the service catches these), step 06 (the §3.6 table keys on
them), step 07 (T-1 asserts the charge count).

## 3. Files

```
src/main/java/com/glofox/memberships/payment/
├── PaymentGateway.java                   MODIFY  ← vendor, strictly additive
└── AlwaysSucceedingPaymentGateway.java    LEAVE  ← must stay byte-identical

src/test/java/com/glofox/memberships/payment/
├── CountingGateway.java                  CREATE  ← shared fixture for steps 05, 07
└── PaymentPortContractTest.java          CREATE
```

## 4. Step-by-step

### 4.1 `PaymentGateway.java` — append two nested classes, change nothing

```java
public interface PaymentGateway {

    /**
     * Attempt to charge {@code amountCents} for a purchase.
     *
     * @throws PaymentFailedException when the charge is declined or the gateway errors
     */
    PaymentResult charge(int amountCents, String memberName);

    record PaymentResult(String transactionId) {
    }

    class PaymentFailedException extends RuntimeException {
        public PaymentFailedException(String message) {
            super(message);
        }
    }

    // ----- added in step 04 (TRD §1.7) -------------------------------------

    /**
     * A working gateway refused the charge. A definitive answer; not retryable
     * as-is. Maps to {@code 402 payment_declined}.
     */
    class PaymentDeclinedException extends PaymentFailedException {
        public PaymentDeclinedException(String message) {
            super(message);
        }
    }

    /**
     * The gateway could not be reached or errored before deciding. No answer at
     * all; retryable. Maps to {@code 502 payment_gateway_error}.
     */
    class PaymentGatewayUnavailableException extends PaymentFailedException {
        public PaymentGatewayUnavailableException(String message) {
            super(message);
        }
    }
}
```

**The vendor javadoc on `charge` already named both conditions.** It reads
*"when the charge is declined **or** the gateway errors"* — two distinct
conditions under one type. The extension does not invent a distinction; it makes
the port's own documented contract expressible. That is the strongest available
answer to "why did you feel entitled to change their interface?", stronger than
pointing at the stretch-goal list.

**Four mechanical points:**

1. **No `public` modifier**, matching the existing `PaymentFailedException`.
   Nested types in an interface are implicitly `public static`; adding the
   keyword would make the new code stylistically inconsistent with the three
   members above it.
2. **Subtypes of `PaymentFailedException`, not siblings.** The umbrella survives,
   so the README's instruction to throw `PaymentGateway.PaymentFailedException`
   in tests still works, an adapter that genuinely cannot tell the two apart has
   something correct to throw, and `catch (PaymentFailedException)` catches both
   (TRD §1.7).
3. **The existing `charge` signature and its javadoc are left byte-identical.**
   Adding `@throws` lines for the new subtypes would be useful and would turn the
   diff from "three classes added" into "a vendor method's documentation edited".
   Provenance wins; the new classes sit immediately below in the same file, so
   they are visible to any reader of `charge`. *(Noted as a judgement call, not
   an oversight.)*
4. **`AlwaysSucceedingPaymentGateway` is not touched and does not need to be** —
   it implements only `charge`, and `charge` did not change. Verification #3
   proves this rather than asserting it.

### 4.2 `CountingGateway.java` — the shared test fixture

TRD §5.2: a hand-written stub beats a Mockito mock here, because it can **count**
— and F-5 showed that the charge count is the assertion which catches an
implementation that charges everyone and persists five.

```java
package com.glofox.memberships.payment;

/** Test gateway. Counts every attempt, including failed ones. */
public final class CountingGateway implements PaymentGateway {

    private final AtomicInteger charges = new AtomicInteger();
    private final Queue<Integer> amounts = new ConcurrentLinkedQueue<>();
    private final Supplier<PaymentResult> behaviour;

    private CountingGateway(Supplier<PaymentResult> behaviour) {
        this.behaviour = behaviour;
    }

    public static CountingGateway succeeding() {
        return new CountingGateway(() -> new PaymentResult("txn-" + UUID.randomUUID()));
    }

    public static CountingGateway declining() {
        return new CountingGateway(() -> {
            throw new PaymentDeclinedException("card declined");
        });
    }

    public static CountingGateway unavailable() {
        return new CountingGateway(() -> {
            throw new PaymentGatewayUnavailableException("gateway timeout");
        });
    }

    @Override
    public PaymentResult charge(int amountCents, String memberName) {
        charges.incrementAndGet();        // BEFORE behaviour — failures count too
        amounts.add(amountCents);
        return behaviour.get();
    }

    public int charges()            { return charges.get(); }
    public List<Integer> amounts()  { return List.copyOf(amounts); }
}
```

Three deliberate details:

- **The counter increments *before* the behaviour runs.** Otherwise a declining
  gateway would report zero charges, and T-2's "the gateway was called exactly
  once even though it failed" assertion would be vacuous.
- **`AtomicInteger` and `ConcurrentLinkedQueue`**, because T-1 calls this from
  500 virtual threads. A plain `int` would under-count and make the headline test
  flaky in the direction of *passing*.
- **`amounts()` is cheap and worth having.** It lets later steps assert the
  amount charged equals the plan price in cents — FR-5(a) and AC-3 — rather than
  only that *a* charge happened.

Three static factories rather than a boolean or enum flag, so call sites read as
`CountingGateway.declining()` and the test's intent is in the construction.

### 4.3 `PaymentPortContractTest.java`

§1.7's argument for subtypes-over-siblings is a *design claim*, and a design
claim that only exists in prose is one refactor away from being false. These
tests make it executable.

| Test | Asserts | Why it exists |
|---|---|---|
| `declineIsCaughtByUmbrellaType` | `PaymentDeclinedException` is an instance of `PaymentFailedException` | **The core §1.7 claim.** Fails loudly if anyone "tidies up" to sibling types |
| `outageIsCaughtByUmbrellaType` | same for `PaymentGatewayUnavailableException` | as above |
| `declineAndOutageAreDistinctTypes` | neither is assignable to the other | §3.6 keys its table on exact class; if they collapsed, two rows would alias |
| `defaultBeanStillSucceeds` | `new AlwaysSucceedingPaymentGateway().charge(2500, "Alice")` returns a non-blank `transactionId` | the vendor bean still works unmodified |
| `failuresAreCounted` | `declining()` → `charges() == 1` after one throwing call | the fixture's counter is trustworthy before F-5 relies on it |
| `amountIsRecorded` | `succeeding().charge(2500, "Alice")`, then `amounts()` is `[2500]` | the amount assertion is trustworthy |

Plain JUnit 6 + AssertJ, no Spring context. Both are on the classpath already
(`codebase-context.md` constraint 11) — **nothing is added to `build.gradle.kts`
in this step or any other.**

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Full suite
./gradlew test --console=plain

# 2. This step's tests
./gradlew test --tests '*PaymentPortContractTest' --console=plain

# 3. THE PROVENANCE CHECK — the vendor diff must be purely additive.
#    No removed lines at all in PaymentGateway, and no diff whatsoever elsewhere.
git diff HEAD -- src/main/java/com/glofox/memberships/payment/PaymentGateway.java \
  | grep '^-[^-]' \
  && echo "FAIL: lines were REMOVED from the vendor port" \
  || echo "OK: PaymentGateway diff is additive only"

git diff --stat HEAD -- src/main/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGateway.java \
                        src/main/java/com/glofox/memberships/config/SeedData.java \
                        src/main/java/com/glofox/memberships/MembershipsApplication.java
# expect: empty output

# 4. The charge signature is untouched (the sealed-result-type alternative would
#    have changed this line; prove it did not)
grep -n "PaymentResult charge(int amountCents, String memberName);" \
     src/main/java/com/glofox/memberships/payment/PaymentGateway.java
# expect: exactly one match

# 5. No new dependencies were added anywhere in phase 3
git diff HEAD -- build.gradle.kts
# expect: empty output

# 6. The exception hierarchy is what §1.7 claims (belt and braces alongside #2)
./gradlew test --tests '*PaymentPortContractTest.declineIsCaughtByUmbrellaType' \
               --tests '*PaymentPortContractTest.declineAndOutageAreDistinctTypes' \
               --console=plain
```

**Done when:** all six pass, `git status` shows 2 new test files and exactly 1
modified production file (`PaymentGateway.java`).

## 6. Notes for the next step

Step 05 builds `PurchaseService` and is the largest step in the plan. What it
inherits from here:

- **It catches `RuntimeException`, not `PaymentFailedException`** (TRD §4.9).
  That is correct under the subtype design chosen here *and* would stay correct
  if the hierarchy were ever flattened — which is the reason §4.2's audit picked
  the broad catch.
- **It never names these types to decide cleanup.** Cleanup is one path for all
  failures; only step 06's mapping table cares which type it was.
- `CountingGateway.declining()` and `.unavailable()` are the two parameters of
  step 05's single parameterised T-2/T-10 test.

**Running total of vendor-file changes after this step: two.**
`application.yml` (one additive key, step 03) and `PaymentGateway.java` (three
additive classes, this step). Both go in `SUBMISSION.md` at step 08.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 04
LAST APPROVED: step_03 (GET /v1/plans end to end)
NEXT STEP: Review step_04. On approval -> step_05_purchase_service.md
==================================================
```
