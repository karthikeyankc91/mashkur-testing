# Step 02 — In-memory adapters, seeding, and the clock

## 1. Context & Objective

Implement the four ports from step 01 in `storage/memory/`, then wire them as
beans that are **constructed already seeded**. This step contains both atomic
primitives — the whole concurrency story of the exercise lives in about fifteen
lines here.

**Objective:** the application boots with a populated catalogue, the two atomic
primitives are proven correct by test, and an unrecognised seed type fails the
boot rather than a request.

**Traces to:** TRD §1.4.4 (mechanism), §1.8 (seeding and wiring), §1.6 (clock),
§2.2 (catalogue vs inventory), §2.3 (inventory representation), §2.5 (ports),
§2.6 (seed mapping). PRD FR-1, AC-1, NFR-1, NFR-3, NFR-9, AS-8.

## 2. Dependencies

| Needs | From |
|---|---|
| `domain/` records and `MemberKey` | step 01 |
| The four `store/` port interfaces | step 01 |
| `SeedData.PLANS` (vendor, read-only) | skeleton |

**Blocks:** step 03 (`GET /plans` reads the catalogue and inventory) and step 05
(the service calls all four ports).

## 3. Files

```
src/main/java/com/glofox/memberships/
├── storage/memory/
│   ├── InMemoryPlanCatalogue.java         CREATE
│   ├── InMemoryInventoryStore.java        CREATE   ← NFR-1 lives here
│   ├── InMemoryUnlimitedClaimStore.java   CREATE   ← NFR-3 lives here
│   ├── InMemoryPurchaseStore.java         CREATE
│   └── package-info.java                  LEAVE (vendor)
└── config/
    ├── PlanSeedMapper.java                CREATE   (see 4.6 — deviation noted)
    ├── SeedingConfig.java                 CREATE
    ├── ClockConfig.java                   CREATE
    └── SeedData.java                      LEAVE (vendor, must not be edited)

src/test/java/com/glofox/memberships/
├── storage/memory/
│   ├── InMemoryInventoryStoreTest.java      CREATE
│   └── InMemoryUnlimitedClaimStoreTest.java CREATE
└── config/PlanSeedMapperTest.java           CREATE
```

## 4. Step-by-step

### 4.1 `InMemoryPlanCatalogue`

```java
public final class InMemoryPlanCatalogue implements PlanCatalogue {

    private final List<Plan> ordered;        // PRD FR-2 stable seed order
    private final Map<UUID, Plan> byId;      // O(1) lookup for the service

    public InMemoryPlanCatalogue(List<Plan> plans) {
        this.ordered = List.copyOf(plans);
        this.byId = plans.stream()
            .collect(Collectors.toUnmodifiableMap(Plan::id, Function.identity()));
    }

    public Optional<Plan> find(UUID planId)  { return Optional.ofNullable(byId.get(planId)); }
    public List<Plan> listInSeedOrder()      { return ordered; }
}
```

**No synchronization, and that is the point.** Both structures are immutable
after construction, so the catalogue is thread-safe by immutability (TRD §2.2).
Holding two views of the same data is safe *only* because neither can change —
with a mutable catalogue they could disagree.

**Free validation:** `toUnmodifiableMap` throws on a duplicate key, so duplicate
seed ids fail the boot. Nothing extra to write.

### 4.2 `InMemoryInventoryStore` — NFR-1

```java
public final class InMemoryInventoryStore implements InventoryStore {

    /** IMMUTABLE map, mutable values. Key set is fixed at construction. */
    private final Map<UUID, AtomicInteger> slots;

    public InMemoryInventoryStore(Map<UUID, Integer> finiteInventoryOnly) {
        this.slots = finiteInventoryOnly.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> new AtomicInteger(e.getValue())));
    }

    @Override public boolean tryReserve(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        if (counter == null) return true;                        // absent == unlimited
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0; // claimed iff it WAS positive
    }

    @Override public void release(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        if (counter != null) counter.incrementAndGet();           // no-op when unlimited
    }

    @Override public OptionalInt remaining(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        return counter == null ? OptionalInt.empty() : OptionalInt.of(counter.get());
    }
}
```

Four things to get right, each with a reason:

1. **`Map.toUnmodifiableMap`, not `ConcurrentHashMap`.** The key set never
   changes — only the `AtomicInteger` values mutate. A concurrent map here would
   be protecting something that cannot be modified (TRD §2.2).
2. **`getAndUpdate`, not `updateAndGet`.** The *previous* value is what says
   whether you won. `updateAndGet` returning `0` cannot distinguish "I took the
   last slot" from "there were none".
3. **The guard lives inside the lambda.** `n > 0 ? n - 1 : n` means there is no
   window between testing and decrementing, so the counter is structurally
   incapable of going negative.
4. **`null` short-circuits before any arithmetic.** Absence *is* unlimited
   inventory — PRD T-4 is retired by construction, not by a comparison that
   could be written backwards (TRD §2.3, §4.1).

**A safety net deliberately not added:** `release` could clamp at the seeded
maximum so inventory can never exceed its starting value. Rejected — clamping
would *mask* a double-release, which is exactly the F-3 bug that T-12 exists to
catch. Exactly-once release is the caller's obligation (TRD §2.5), and the
counter's final value is the assertion that proves it.

### 4.3 `InMemoryUnlimitedClaimStore` — NFR-3

```java
public final class InMemoryUnlimitedClaimStore implements UnlimitedClaimStore {

    private final ConcurrentMap<MemberKey, UUID> claims = new ConcurrentHashMap<>();

    @Override public boolean tryClaim(MemberKey key, UUID purchaseId) {
        return claims.putIfAbsent(key, purchaseId) == null;   // null exactly once per key
    }

    @Override public boolean release(MemberKey key, UUID purchaseId) {
        return claims.remove(key, purchaseId);                // only if it still owns it
    }
}
```

**`ConcurrentHashMap` is genuinely required here** — unlike §4.2, these keys are
added and removed at runtime. The contrast between the two stores is worth being
able to explain.

**The one-unlimited rule is enforced by the map, not by any comparison we
write.** `MemberKey` is a record whose canonical constructor normalised the
value in step 01, so its generated `equals`/`hashCode` work on the folded form.
`putIfAbsent` therefore collides `"Alice"` with `"alice"` with `" ALICE "`
without a single line of matching logic. Step 01's normalisation and this
`putIfAbsent` are one mechanism split across two files.

**`remove(key, value)`** is the two-argument form, for the stale-cancel scenario
in TRD §2.5. It returns `false` rather than throwing, so the caller can log an
invariant breach (TRD §1.4.3).

### 4.4 `InMemoryPurchaseStore`

```java
public final class InMemoryPurchaseStore implements PurchaseStore {

    private final ConcurrentMap<UUID, Purchase> purchases = new ConcurrentHashMap<>();

    @Override public void save(Purchase purchase)      { purchases.put(purchase.id(), purchase); }
    @Override public Optional<Purchase> findById(UUID id) { return Optional.ofNullable(purchases.get(id)); }

    // Tier 2 only (F-3)
    @Override public boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next) {
        Purchase current = purchases.get(id);
        if (current == null || current.status() != expected) return false;
        return purchases.replace(id, current, current.withStatus(next));
    }
}
```

`replace(key, oldValue, newValue)` compares by `equals`, and `Purchase` is a
record with structural equality — so if two threads both read the same `current`
and both compute `withStatus`, the first `replace` succeeds and the second
returns `false` because the stored value no longer equals what it read. That is
F-3's race, closed. **The `status() != expected` check alone is not sufficient**;
it is a fast path, and `replace` is the decision.

### 4.5 `ClockConfig`

```java
@Configuration
class ClockConfig {
    @Bean Clock clock() { return Clock.systemDefaultZone(); }   // PRD AS-8: server zone
}
```

The only `Clock` bean in the system. Step 05 injects it into `StartDatePolicy`
and nowhere else (TRD §1.6).

### 4.6 `PlanSeedMapper` — a small deviation from the TRD, flagged

TRD §1.8 sketched `toPlan` and `parseType` as **private** methods inside
`SeedingConfig`. Private methods on a `@Configuration` class cannot be unit
tested, and §2.6's requirement — *an unrecognised type must fail the boot* —
cannot be exercised through `SeedData`, which is immutable vendor code and must
not be edited.

So the mapping moves to a package-private helper:

```java
final class PlanSeedMapper {                     // package-private, config package

    private PlanSeedMapper() { }

    static Plan toPlan(SeedData.PlanSeed seed) {
        return new Plan(seed.id(), seed.name(), parseType(seed.type()),
                        seed.active(), seed.priceCents(), seed.credits());
    }

    static PlanType parseType(String raw) {
        return switch (raw) {
            case "unlimited" -> PlanType.UNLIMITED;
            case "pack"      -> PlanType.PACK;
            default -> throw new IllegalStateException("Unknown plan type: " + raw);
        };
    }
}
```

Same behaviour, now testable. This is the only intentional divergence from the
TRD in this step; record it in `SUBMISSION.md` (step 08).

### 4.7 `SeedingConfig` — constructed already seeded

```java
@Configuration
class SeedingConfig {

    @Bean PlanCatalogue planCatalogue() {
        return new InMemoryPlanCatalogue(
            SeedData.PLANS.stream().map(PlanSeedMapper::toPlan).toList());
    }

    @Bean InventoryStore inventoryStore() {
        return new InMemoryInventoryStore(
            SeedData.PLANS.stream()
                .filter(s -> s.remainingSlots() != null)        // §2.3: absent == unlimited
                .collect(Collectors.toMap(SeedData.PlanSeed::id,
                                          SeedData.PlanSeed::remainingSlots)));
    }

    @Bean UnlimitedClaimStore unlimitedClaimStore() { return new InMemoryUnlimitedClaimStore(); }
    @Bean PurchaseStore       purchaseStore()       { return new InMemoryPurchaseStore(); }
}
```

- **No empty state ever exists.** The bean does not exist until it is populated,
  which is a stronger guarantee than seeding after construction (TRD §1.8).
- **The filter is the whole of `null`-means-unlimited.** Only Launch Promo Pack
  contributes an entry; the other three have no counter at all.
- **Seed failure kills the boot for free.** `parseType` throwing inside a `@Bean`
  method fails context refresh and the application exits non-zero. Nothing to
  write.
- **The adapters carry no Spring annotations.** They are plain classes this
  config constructs, which is why they are unit-testable with no context — and
  is asserted by verification #4 below.

### 4.8 Tests

**`InMemoryInventoryStoreTest`** — single-threaded boundaries first:

| Test | Asserts |
|---|---|
| `absentPlanIsUnlimited` | 1,000 × `tryReserve(unknownId)` all `true`; `remaining` is `empty` |
| `finiteReservesExactlyN` | 5 slots → 5 × `true`, then `false`; `remaining` is `0` |
| `neverNegative` | after exhaustion, 100 more attempts all `false`, `remaining` still `0` |
| `releaseRestores` | exhaust, release once, `remaining` is `1`, next `tryReserve` is `true` |
| `releaseOnUnlimitedIsNoop` | `release(unknownId)` does not throw; `remaining` still `empty` |
| `zeroSeededIsSoldOutFromBoot` | a plan seeded at `0` has an entry and `tryReserve` is `false` — `0` is not `null` |

Plus **one narrow concurrency test on the primitive**: 500 virtual threads
calling `tryReserve` directly on a 5-slot counter → exactly 5 `true`, final value
`0`. This is **not** a duplicate of T-1: this proves the *primitive*, T-1 (step
07) proves the *orchestration* including the charge count. If both fail you know
it is the store; if only T-1 fails you know it is the service.

**`InMemoryUnlimitedClaimStoreTest`**:

| Test | Asserts |
|---|---|
| `claimOnceOnly` | second `tryClaim` for the same key is `false` |
| `caseAndWhitespaceCollide` | `MemberKey("Alice")` then `MemberKey(" alice ")` → second `false`. **Proves step 01's normalisation and this map are one mechanism** |
| `releaseOnlyIfOwner` | claim with `P1`, `release(key, P2)` is `false`, claim still held |
| `releaseThenReclaim` | `release(key, P1)` is `true`, then `tryClaim` succeeds again (D-9) |

**`PlanSeedMapperTest`**:

| Test | Asserts |
|---|---|
| `mapsBothTypes` | `"unlimited"` → `UNLIMITED`, `"pack"` → `PACK` |
| `unknownTypeThrows` | `parseType("subscription")` throws `IllegalStateException` — §2.6's fail-fast |
| `mapsAllFourSeedPlans` | all of `SeedData.PLANS` map without throwing; Legacy Plan has `active == false` |

## 5. Verification

```bash
cd /Users/karthikeyanchandrarajan/Documents/GitHub/learn

# 1. Full suite, including the vendor contextLoads test
./gradlew test --console=plain

# 2. The two atomic primitives specifically
./gradlew test --tests '*InMemoryInventoryStoreTest' \
               --tests '*InMemoryUnlimitedClaimStoreTest' \
               --tests '*PlanSeedMapperTest' --console=plain

# 3. Import boundary — storage/memory must not reach outward, and needs no Spring
grep -rn "org\.springframework\|jackson\|com\.glofox\.memberships\.web\|com\.glofox\.memberships\.service" \
     src/main/java/com/glofox/memberships/storage \
  && echo "FAIL: adapter reached outward" \
  || echo "OK: adapters are plain java over domain + store"

# 4. The clock exists in exactly one place (TRD §1.6 is structural, so prove it)
grep -rln "java\.time\.Clock" src/main/java | sort
# expect exactly: .../config/ClockConfig.java
#   NOTE (F-6): correct as of THIS step. Step 05 adds two more holders —
#   service/StartDatePolicy.java and service/PurchaseFactory.java — because
#   FR-6's createdAt needs the time too. The lasting invariant is not "one
#   file" but "no class exposes the current time"; see TRD §1.6 as amended.

# 5. The app boots with a populated catalogue (PRD AC-1, NFR-9)
./gradlew bootRun --console=plain 2>&1 | grep -E "id=.*name=" | wc -l    # expect 4
#   then Ctrl-C

# 6. Vendor files untouched
git diff --stat HEAD -- src/main/java/com/glofox/memberships/config/SeedData.java \
                        src/main/java/com/glofox/memberships/payment \
                        src/main/java/com/glofox/memberships/storage/memory/package-info.java
# expect: empty output
```

**`contextLoads()` has quietly become a real test.** The vendor's placeholder
asserted only that Spring starts. Now that seeding happens during bean
construction, it also asserts that all four plans map successfully — a bad
`parseType` fails it. Worth noting in the follow-up: the trivial test became
meaningful because of *where* the seeding was placed.

**Done when:** all six pass, `git status` shows 9 new files and 0 modified.

## 6. Notes for the next step

Step 03 builds `GET /v1/plans` on top of this and needs nothing else — no
service, no payment, no clock. It reads `listInSeedOrder()` and calls
`remaining(id)` per plan, mapping `OptionalInt.empty()` to a JSON `null` at the
assembler. That is the single place in the system where "unlimited inventory"
becomes a null (TRD §3.2).

```
==================================================
CURRENT PHASE: Phase 3 [Modular Implementation Plan] -> Step 02
LAST APPROVED: 00_INDEX.md decomposition + step_01
NEXT STEP: Review step_02. On approval -> step_03_plans_endpoint.md
==================================================
```
