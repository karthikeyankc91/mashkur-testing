# Step 01 — Domain types and store ports

## 1. Context & Objective

Create the domain model and the four storage ports. **Nothing in this step
imports Spring, Jackson, or `jakarta.*`** — these are plain Java records,
enums and interfaces.

This step exists first because TRD §1.4 established that the atomic operations
the service needs *are* the port methods (§2.5), and TRD §1.2 established that
every dependency arrow points inward to `domain`. Writing the innermost layer
first means nothing later can accidentally invert it.

**Objective:** `./gradlew test` stays green, `MemberKey` normalisation is proven
by test, and a grep proves the import boundary holds.

**Traces to:** TRD §2.1 (domain types), §2.4 (member key), §2.5 (port
contracts), §1.1 (layout and forbidden imports), §1.2 (dependency direction).
PRD FR-6, D-4, D-7.

## 2. Dependencies

| Needs | Status |
|---|---|
| Gradle wrapper working | ✅ fixed in `9f52e7a` |
| `./gradlew test` green | ✅ verified |
| Nothing else | this is the first step |

**Blocks:** steps 02 (adapters implement these ports) and 05 (service calls
them).

## 3. Files

All created; nothing modified. No vendor file is touched.

```
src/main/java/com/glofox/memberships/
├── domain/
│   ├── PlanType.java             CREATE
│   ├── Plan.java                 CREATE
│   ├── PlanSnapshot.java         CREATE
│   ├── PurchaseStatus.java       CREATE
│   ├── Purchase.java             CREATE
│   └── MemberKey.java            CREATE
└── store/
    ├── PlanCatalogue.java        CREATE
    ├── InventoryStore.java       CREATE
    ├── UnlimitedClaimStore.java  CREATE
    └── PurchaseStore.java        CREATE

src/test/java/com/glofox/memberships/
└── domain/MemberKeyTest.java     CREATE
```

## 4. Step-by-step

### 4.1 `domain/PlanType.java`

```java
package com.glofox.memberships.domain;

public enum PlanType { UNLIMITED, PACK }
```

No `@JsonValue`. The lowercase wire form (`"unlimited"`) is produced by the
assembler in step 03 — TRD §3.1 keeps web concerns out of the domain.

### 4.2 `domain/Plan.java`

```java
public record Plan(UUID id, String name, PlanType type, boolean active,
                   int priceCents, Integer credits) { }
```

- **No `remainingSlots` field.** Inventory is a separate structure (TRD §2.2);
  this is the decision that makes the catalogue immutable and lock-free to read.
- `credits` is a nullable `Integer` — `null` means "not a pack, concept does not
  apply". This is genuine absence, unlike inventory (TRD §2.1).
- `int` for cents, matching `PaymentGateway.charge(int, …)`.

### 4.3 `domain/PlanSnapshot.java`

```java
public record PlanSnapshot(UUID planId, String name, PlanType type,
                           int priceCents, Integer credits) {

    public static PlanSnapshot of(Plan plan) { /* copy all five fields */ }
}
```

D-7: a purchase records what was sold, so a later price change cannot rewrite
history. The `of` factory is the only way one is built.

### 4.4 `domain/PurchaseStatus.java`

```java
public enum PurchaseStatus { ACTIVE, CANCELLED }
```

**No `FAILED`.** PRD FR-5(f) means a failed purchase never persists, so there is
no state for it to be in. `CANCELLED` is reachable only in Tier 2.

### 4.5 `domain/Purchase.java`

```java
public record Purchase(UUID id, String memberName, MemberKey memberKey,
                       PlanSnapshot plan, int amountCents, PurchaseStatus status,
                       LocalDate startDate, String transactionId,
                       Instant createdAt) {

    // Tier 2 only. Records are immutable, so cancel replaces rather than mutates.
    public Purchase withStatus(PurchaseStatus next) { /* copy with new status */ }
}
```

- **Both `memberName` and `memberKey`.** The first is what the member typed, for
  display; the second is the normalised match key. TRD §3.3: `memberKey` never
  appears on the wire.
- `startDate` is a `LocalDate`, `createdAt` is an `Instant` — two different kinds
  of time, deliberately two types (TRD §3.1).

### 4.6 `domain/MemberKey.java` — the only real logic in this step

```java
package com.glofox.memberships.domain;

import java.util.Locale;
import java.util.Objects;

public record MemberKey(String value) {

    public MemberKey {
        Objects.requireNonNull(value, "member name");
        value = value.strip().toLowerCase(Locale.ROOT);   // reassigns the parameter
        if (value.isEmpty()) {
            throw new IllegalArgumentException("member name must not be blank");
        }
    }
}
```

Three details that are **not** stylistic (TRD §2.4):

1. **Compact constructor, not a static factory.** Reassigning the parameter means
   *every* construction normalises. An unnormalised `MemberKey` cannot exist. A
   `MemberKey.of(...)` factory would leave `new MemberKey(raw)` reachable.
2. **`strip()`, not `trim()`.** `trim()` only removes characters at or below
   `U+0020`. A name pasted with a non-breaking space normalises under `strip()`
   and silently does not under `trim()`.
3. **`toLowerCase(Locale.ROOT)`, not `toLowerCase()`.** Under a Turkish default
   locale, `"ALICE".toLowerCase()` yields `"alıce"` with a dotless ı — so
   `"ALICE"` and `"alice"` would be **different members** and the one-unlimited
   rule would be bypassed by server configuration rather than by anything in the
   request.

### 4.7 The four ports in `store/`

Exactly as TRD §2.5 specifies. Interfaces only; no implementations in this step.

```java
public interface PlanCatalogue {
    Optional<Plan> find(UUID planId);
    List<Plan> listInSeedOrder();                 // PRD FR-2 stable order
}

public interface InventoryStore {
    boolean tryReserve(UUID planId);              // true if claimed, or inventory unlimited
    void    release(UUID planId);                 // no-op when unlimited
    OptionalInt remaining(UUID planId);           // empty == unlimited
}

public interface UnlimitedClaimStore {
    boolean tryClaim(MemberKey key, UUID purchaseId);
    boolean release(MemberKey key, UUID purchaseId);   // only if it still owns the claim
}

public interface PurchaseStore {
    void save(Purchase purchase);
    Optional<Purchase> findById(UUID id);

    // Tier 2 only (F-3): the status transition and the inventory restore must be
    // one decision, not read-then-write.
    boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next);
}
```

**Two signatures carry non-obvious weight — write the javadoc, not just the
method:**

- **`remaining` returns `OptionalInt`, and no `int`-returning overload may ever
  be added.** A convenience returning `0` for unlimited reintroduces PRD T-4 in
  one line at a distant call site (TRD §4.1).
- **`release(key, purchaseId)` takes the purchase id** so it maps to
  `ConcurrentHashMap.remove(key, value)`. Without it: member buys unlimited
  (claim → P1), cancels P1, buys again (claim → P2), a duplicated cancel of P1
  arrives and releases **P2's** claim (TRD §2.5).

### 4.8 `MemberKeyTest.java`

Four cases, each defending one decision above:

| Test | Asserts |
|---|---|
| `equivalentNames` | `"Alice"`, `"alice"`, `"ALICE"`, `" Alice "` all produce equal keys |
| `unicodeWhitespaceStripped` | `" Alice "` equals `"alice"` — the `strip()`/`trim()` distinction |
| `blankRejected` | `""`, `"   "`, `" "` each throw `IllegalArgumentException` |
| `nullRejected` | `null` throws `NullPointerException` |

Plain JUnit. No Spring context, which is also §1.2's boundary check in practice.

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Compiles, and the existing placeholder test still passes
./gradlew test --console=plain

# 2. MemberKey specifically
./gradlew test --tests '*MemberKeyTest' --console=plain

# 3. THE IMPORT BOUNDARY — TRD §1.1's forbidden-import table, made executable.
#    Must print nothing. This is the check that turns a diagram into a constraint.
grep -rn "org\.springframework\|jackson\|jakarta\." \
     src/main/java/com/glofox/memberships/domain \
     src/main/java/com/glofox/memberships/store \
  && echo "FAIL: web/framework type leaked into domain or store" \
  || echo "OK: domain and store import java.* only"

# 4. No implementations smuggled into the ports
grep -c "class " src/main/java/com/glofox/memberships/store/*.java   # expect 0 each

# 5. Nothing vendor-owned was touched
git diff --stat HEAD -- src/main/java/com/glofox/memberships/config \
                        src/main/java/com/glofox/memberships/payment \
                        src/main/java/com/glofox/memberships/MembershipsApplication.java
# expect: empty output
```

**Done when:** all five pass, and `git status` shows 11 new files and zero
modified files.

## 6. Notes for the next step

Step 02 implements these four ports in `storage/memory/`. Two things it inherits
from here:

- `InventoryStore` entries exist **only** for finite-inventory plans. Absence is
  unlimited — so the adapter's map is built by filtering `remainingSlots != null`
  (TRD §2.3, §2.6).
- The inventory map's **key set never changes**, so it should be an immutable
  `Map` of `AtomicInteger`, not a `ConcurrentHashMap`. The claim store is the
  opposite case and does need one (TRD §2.2).

## 7. Deviations found during execution

### DV-2 — `strip()` is not sufficient; the NBSP example in §4.6 was wrong

**§4.6 claimed:** "a name pasted with a non-breaking space normalises under
`strip()` and silently does not under `trim()`."

**That is false, and the test caught it.** `String.strip()` removes characters
accepted by `Character.isWhitespace(char)`, and Java deliberately excludes
non-breaking spaces from that predicate because they are not line-break
opportunities. So `Character.isWhitespace('\u00A0')` is `false` and `strip()`
leaves a trailing NBSP exactly where `trim()` does.

Two Unicode space predicates exist and they disagree:

| Character | `isWhitespace` | `isSpaceChar` | `trim()` | `strip()` |
|---|---|---|---|---|
| `U+0020` space | yes | yes | removes | removes |
| `\t` `\n` | yes | **no** | removes | removes |
| `U+2003` em space | yes | yes | leaves | removes |
| `U+00A0` no-break space | **no** | yes | leaves | **leaves** |

**Why this is a correctness bug and not a nitpick:** `"Alice"` and
`"Alice\u00A0"` would be different `MemberKey`s, so one member could hold two
active unlimited memberships by appending an invisible character. That defeats
PRD FR-5(d) — a core business rule — through the request body, with no server
misconfiguration needed. It is the same class of bypass as the Turkish-locale
`toLowerCase()` problem that §4.6 got right.

**Fix applied:** `MemberKey` trims with a private `isSpace(char)` accepting
`Character.isWhitespace(c) || Character.isSpaceChar(c)`, so both families are
removed from the leading and trailing edges. `MemberKeyTest` asserts the NBSP,
em-space and mixed cases, and the blank-rejection parameterisation now includes
`U+00A0` and `U+2003`.

**Deliberately still out of scope**, and recorded as a known gap alongside
security finding V-1: interior spacing (`"Al  ice"`), zero-width characters
(`U+200B`), and homoglyph confusables (`"\u0410lice"` with a Cyrillic A).
Closing those needs a real member registry rather than a display name used as
an identity, which is the actual design flaw underneath V-1.

**Lesson for the remaining steps:** the step files were written without
compiling anything. This one was wrong in a way that only a test could reveal,
which is an argument for the step-07 mutation-testing discipline rather than
against the plan.

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 01
LAST APPROVED: TRD as a whole (merged 32ed602)
NEXT STEP: Review the decomposition in 00_INDEX.md and this step file.
           On approval -> step_02_memory_stores_and_seeding.md
==================================================
```
