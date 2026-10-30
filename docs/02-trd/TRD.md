# TRD — Glofox Memberships Purchase API

> **STATUS: Phase 2.2 in progress.** Skeleton approved. §1.4, §2, §3, §4
> approved; F-1/F-3/F-4 applied. **§5 expanded — awaiting review.**
> Still unexpanded: **§1.1–1.3, §1.5–1.8** only.
>
> Expansion ran out of the proposed order at the author's direction: §3, §4 and
> §5 were pulled ahead of the remaining architecture sub-sections.
>
> ⚠️ **§5 found a sixth issue, F-5** — PRD T-1's assertions were satisfiable by a
> wrong implementation. Applied to PRD T-1; see §6.

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

**The unwind must not be able to throw (F-1).** "Unwind in reverse, rethrow"
reads fine and hides a hole: if the first `release()` fails, every later claim
leaks *and* the original exception is replaced by the cleanup's, so the client
gets a misleading error over inconsistent state and the real cause vanishes from
the logs. Required shape:

```java
catch (RuntimeException original) {
    for (Runnable release : reversed(claims)) {
        try { release.run(); }
        catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);   // keep the real cause
            log.error("compensation failed", cleanupFailure);
        }
    }
    throw original;
}
```

Every release is attempted; failures attach to the original rather than
replacing it. A failure handler that can fail is not a failure handler.

**`catch (RuntimeException)`, deliberately not `catch (PaymentFailedException)`.**
§1.7 has not yet decided whether the decline and outage types are siblings or
subtypes. Catching the broad type is correct under either outcome; catching the
payment type would, under the sibling design, miss one and silently keep a promo
slot on every `502` (§4.9).

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

All records, all immutable. `Plan` carries **no** `remainingSlots` — see §2.2.

```java
enum PlanType       { UNLIMITED, PACK }
enum PurchaseStatus { ACTIVE, CANCELLED }

record Plan(UUID id, String name, PlanType type, boolean active,
            int priceCents, Integer credits) {}

record PlanSnapshot(UUID planId, String name, PlanType type,
                    int priceCents, Integer credits) {}

record Purchase(UUID id, String memberName, MemberKey memberKey,
                PlanSnapshot plan, int amountCents, PurchaseStatus status,
                LocalDate startDate, String transactionId, Instant createdAt) {}
```

**`int` for cents, not `long` or `BigDecimal`.** FR-5(a) requires integer cents
and `PaymentGateway.charge(int, String)` fixes the width at the boundary anyway.
Widening inside the domain only to narrow at the port is ceremony.

**Status changes by replacement, not mutation.** `Purchase` is a record, so
cancel (Tier 2) produces a new instance and the store swaps the value. There is
no setter to call from the wrong layer.

**Wrapper types only where the wrapper enforces an invariant.** `MemberKey` is
wrapped because it *normalises* (§2.4) — behaviour, not nominal typing. `UUID`
ids are left bare: a `PlanId`/`PurchaseId` pair would add two types that enforce
nothing, and the two never meet in one signature. The line is "wrap for
invariants, not for labels", and it is worth being able to state.

**Why `credits` stays a nullable `Integer` while `remainingSlots` does not.**
Both are nullable in `PlanSeed`, and the nulls mean opposite things:

| Field | `null` means | Therefore |
|---|---|---|
| `credits` | the concept does not apply — this is not a pack | genuine absence; nullable `Integer` is honest, and `PlanType` already tells you whether to look |
| `remainingSlots` | the concept applies and is **unbounded** | a value in a domain with an infinity element — modelled structurally (§2.3), never as a null to remember |

Same type, same nullability, opposite semantics. Treating them identically is
how PRD T-4 happens.

### 2.2 Catalogue versus inventory

`SeedData.PLANS` is an immutable `List.of` of records, so mutable inventory
cannot live on a plan. That constraint is useful: it forces the split that §1.4.1
then exploits to shrink the contended surface to two claims.

| Structure | Holds | Mutability | Thread-safety from |
|---|---|---|---|
| `PlanCatalogue` | the four `Plan`s, in seed order | **immutable** after seeding | immutability — no synchronization at all |
| `InventoryStore` | one `AtomicInteger` per *finite*-inventory plan | values mutate; **key set never does** | per-value CAS (§1.4.4) |
| `UnlimitedClaimStore` | `MemberKey → purchaseId` | keys added and removed at runtime | `ConcurrentHashMap` |
| `PurchaseStore` | `purchaseId → Purchase` | keys added, values replaced | `ConcurrentHashMap` |

**A precise consequence worth stating aloud:** the inventory map does **not**
need to be a `ConcurrentHashMap`. Its key set is fixed at seeding and never
changes — only the `AtomicInteger` values mutate. An immutable `Map<UUID,
AtomicInteger>` built once is correct and cheaper. The claim store *does* need
`ConcurrentHashMap`, because its keys genuinely come and go. Knowing which of
those two situations you are in is the difference between reasoning about
concurrency and sprinkling concurrent collections around.

**Cost of the split:** `GET /v1/plans` must now compose two sources — a `Plan`
plus `inventory.remaining(id)` — rather than serialising one object. That
composition belongs in the web layer (§3.2). Cheap, and the price of the plan
record being immutable and lock-free to read.

### 2.3 Inventory representation

`remaining_slots` is never a nullable integer in the domain.

- **A finite-inventory plan has an `AtomicInteger` entry.**
- **An unlimited-inventory plan has no entry at all.** Absence *is* unlimited.

So `tryReserve` short-circuits to `true` when the map has no entry for the plan,
and `release` is a no-op. There is no `remaining > 0` comparison anywhere for the
three plans where writing it would be wrong — PRD T-4 is retired by
construction rather than by a test catching it.

Reads cross the boundary as `OptionalInt`:

```java
OptionalInt remaining(UUID planId);   // empty == unlimited inventory
```

`OptionalInt` rather than `Integer` because it forces the caller to handle the
unlimited case explicitly; the DTO maps `empty → JSON null` at the edge (§3.2),
which is the one place the null belongs.

**Rejected: a sentinel.** `-1` or `Integer.MAX_VALUE` for "unlimited" is the
obvious shortcut and the classic defect. `-1` passes a naive `remaining > 0`
guard as *sold out*; `MAX_VALUE` decrements into a wrong-but-plausible number
and overflows if anything ever adds to it. Sentinels put the burden on every
read site remembering, which is precisely what §2.1's null table warns about.

### 2.4 Member identity key

D-4 requires a trimmed, case-folded match key with the submitted name preserved
for display. The normalisation lives in a compact constructor so it cannot be
bypassed:

```java
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

Three deliberate details, each defensible and each a plausible question:

1. **Compact constructor, not a static factory.** A `MemberKey.of(...)` factory
   leaves `new MemberKey(rawName)` reachable and unnormalised. The compact
   constructor reassigns the parameter, so *every* construction — including
   deserialisation — normalises. An unnormalised `MemberKey` cannot exist.
2. **`strip()`, not `trim()`.** `trim()` only removes characters at or below
   `U+0020`; `strip()` is Unicode-aware. A name pasted with a non-breaking space
   normalises correctly under `strip()` and silently does not under `trim()`.
3. **`toLowerCase(Locale.ROOT)`, not `toLowerCase()`.** The no-arg form uses the
   default locale. Under a Turkish locale, `"ALICE".toLowerCase()` yields
   `"alıce"` with a dotless ı, so `"ALICE"` and `"alice"` would be *different
   members* — the one-unlimited rule silently bypassed by server configuration.
   `Locale.ROOT` makes the key deterministic wherever the app runs.

`Purchase` stores both: `memberName` exactly as submitted (display), `memberKey`
normalised (matching). The blank check here is an invariant backstop — validation
rejects blank names first (§1.5) so the client gets a clean `400
validation_failed` rather than a mapped `IllegalArgumentException`.

### 2.5 Store port contracts

Determined by §1.4: the atomic operations the service needs *are* these methods.

```java
interface PlanCatalogue {
    Optional<Plan> find(UUID planId);
    List<Plan> listInSeedOrder();              // FR-2 stable order
}

interface InventoryStore {
    boolean tryReserve(UUID planId);           // true if claimed, or inventory unlimited
    void    release(UUID planId);              // no-op when unlimited
    OptionalInt remaining(UUID planId);        // empty == unlimited
}

interface UnlimitedClaimStore {
    boolean tryClaim(MemberKey key, UUID purchaseId);
    boolean release(MemberKey key, UUID purchaseId);   // only if it still owns the claim
}

interface PurchaseStore {
    void save(Purchase purchase);
    Optional<Purchase> findById(UUID id);

    // F-3, Tier 2 only: the status transition and the inventory restore
    // must be one decision, not read-then-write.
    boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next);
}
```

**`compareAndSetStatus` exists because cancel has the same race as purchase
(F-3).** Two concurrent cancels of one purchase both read `status = ACTIVE`, both
pass a read-then-write guard, and both restore a slot — a 5-slot pack ends with
6. Only the thread that wins the CAS restores inventory and releases the
unlimited claim. In memory this is `ConcurrentHashMap.replace(key, old, new)`;
in SQL it is `UPDATE purchases SET status='CANCELLED' WHERE id=? AND
status='ACTIVE'` with rows-affected = 1 — the same shape as every other claim in
§1.4.7, which is the point: cancel is not a special case, it is a fourth claim.

**The purchase id is generated before step 4 of §1.4.2**, so the claim can
record which purchase owns it.

**`release` takes the purchase id, and that is not symmetry for its own sake.**
It maps to `ConcurrentHashMap.remove(key, value)`, which removes only if the
mapping still points at that purchase. Without it, this sequence corrupts state:
a member buys unlimited (claim → P1), cancels P1 (claim released), buys again
(claim → P2), then a retried or duplicated cancel of P1 arrives and releases
**P2's** claim — leaving a member holding an active unlimited membership and free
to buy another. The two-argument remove closes that for free.

Inventory `release` cannot be made value-safe the same way, so exactly-once
release is an obligation on the caller: §1.4.2's compensation stack pushes each
release once per request, and cancel must check status before restoring (§4.6).

**No `findActiveUnlimitedByMember` on `PurchaseStore`** — deliberately. The
obvious design queries purchases on every attempt, which is both O(n) and racy:
the scan and the subsequent insert are not atomic, which is exactly NFR-3's bug.
`UnlimitedClaimStore` is a purpose-built index that makes the check and the claim
one operation — the in-memory counterpart of a unique constraint.

**The cost, stated:** that index is denormalised state which must stay in step
with `PurchaseStore`. It is written on purchase, released on payment failure, and
released on cancel; those are the only three paths, and all three live in one
service method. A database would move the constraint onto the table and remove
the obligation entirely — which is the honest answer to "what would you change
with a real store?"

### 2.6 Seed mapping

`PlanSeed` carries `type` as a raw `String`. It is parsed once, at startup:

```java
private static PlanType parseType(String raw) {
    return switch (raw) {
        case "unlimited" -> PlanType.UNLIMITED;
        case "pack"      -> PlanType.PACK;
        default -> throw new IllegalStateException("Unknown plan type: " + raw);
    };
}
```

**An unrecognised type must fail the boot, not a request.** Throwing during
seeding means the application refuses to start with a catalogue it cannot model —
far better than serving three plans and `500`ing on the fourth. This is why
seeding runs in a bean initialiser rather than on `ApplicationReadyEvent`
(§1.8): by the time the ready event fires, the port is already accepting traffic.

The mapping also routes the two nullable fields to their separate homes:

- `credits` → onto the `Plan` record, unchanged.
- `remainingSlots` → an `InventoryStore` entry **only when non-null**. Null
  contributes no entry (§2.3).

**This routing is what defuses the Launch Promo Pack coincidence.** That plan has
`credits = 5` *and* `remainingSlots = 5` — identical values for orthogonal
concepts, in the single plan every test hammers hardest. Because the two fields
land in different structures at the seeding boundary, code that confuses them
does not compile rather than passing its tests and failing on `10-Class Pack`
(`credits = 10`, `remainingSlots = null`).

**The mapping lives in `config`, not in the domain.** Nothing in the domain
imports `SeedData` — the seed source is an adapter concern, which is the same
argument as NFR-4 applied to input rather than storage.

---

## 3. API Contracts

> **Classpath facts verified from the resolved `runtimeClasspath`, not assumed.**
> Spring Boot 4.0.0 ships **Jackson 3.0.2** (`tools.jackson.core:jackson-databind`)
> while annotations remain at `com.fasterxml.jackson.core:jackson-annotations:2.20`
> in the **old** package. The split is counterintuitive and costs a confusing
> compile error in both directions:
>
> | Import from | Examples |
> |---|---|
> | `com.fasterxml.jackson.annotation.*` | `@JsonProperty`, `@JsonValue`, `@JsonInclude` |
> | `tools.jackson.databind.*` | `ObjectMapper`, `PropertyNamingStrategies`, modules |
>
> Also verified: `jackson-databind` 3.0.2 carries `java.time` support internally
> (60 JSR-310 classes), so **no `jackson-datatype-jsr310` dependency is needed**
> for `LocalDate` / `Instant`; and `spring.jackson.property-naming-strategy`
> exists in Boot 4's configuration metadata, so §3.1's approach is available.

### 3.1 DTOs and the `snake_case` mapping

**One line of configuration, not per-field annotations:**

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

D-1's reason was "consistency matters more than the choice", and a global
strategy is the only option that *cannot* be applied inconsistently. Per-field
`@JsonProperty` is 20-odd annotations with 20 chances to forget one, and the
failure is silent — a single `camelCase` field in an otherwise `snake_case`
payload. Per-class `@JsonNaming` has the same flaw at class granularity.

**Cost, acknowledged:** the wire contract becomes invisible at the DTO. A reader
of `PurchaseRequest` sees `memberName` and must know the global strategy to infer
`member_name`. Mitigated by a one-line comment on each DTO and a note in
`SUBMISSION.md`; the alternative trades that for a real correctness risk.

**DTOs are separate types from the domain, in the web package.** No Jackson
annotation ever appears on `Plan`, `Purchase`, or `MemberKey`. This is NFR-4
applied at the inbound edge: the domain has no idea it is being serialised, so
the wire format can change without touching it. An assembler maps
domain → DTO.

**Enum wire forms are lowercase, and mapped in the assembler.** The brief's
vocabulary is `"unlimited"` / `"pack"`, but Jackson's default for an enum is
`UNLIMITED`. Rather than annotate the domain enum with `@JsonValue` — which
would put a web concern in the domain — the DTO carries a `String` and the
assembler writes `type.name().toLowerCase(Locale.ROOT)`. `PurchaseStatus`
follows the same rule (`"active"`, `"cancelled"`) so the whole API has one
convention for enums rather than two.

**Two kinds of time, deliberately different types:**

| Field | Type | Why |
|---|---|---|
| `start_date` | `LocalDate` | A calendar date. FR-5(b): no timezone, no instant |
| `created_at` | `Instant` | A real moment in time; serialises as ISO-8601 with `Z` |

Collapsing them to one type is how "timezones are out of scope" becomes
"timezones are a bug".

### 3.2 `GET /v1/plans`

`200 OK`, a bare JSON array in seed order, including the inactive plan.

```json
[
  { "id": "11111111-1111-1111-1111-111111111111", "name": "Monthly Unlimited",
    "type": "unlimited", "active": true,  "price_cents": 8000,
    "credits": null, "remaining_slots": null },
  { "id": "22222222-2222-2222-2222-222222222222", "name": "10-Class Pack",
    "type": "pack",      "active": true,  "price_cents": 5000,
    "credits": 10,   "remaining_slots": null },
  { "id": "33333333-3333-3333-3333-333333333333", "name": "Launch Promo Pack",
    "type": "pack",      "active": true,  "price_cents": 2500,
    "credits": 5,    "remaining_slots": 5 },
  { "id": "44444444-4444-4444-4444-444444444444", "name": "Legacy Plan",
    "type": "unlimited", "active": false, "price_cents": 6000,
    "credits": null, "remaining_slots": null }
]
```

**A bare array, not `{"plans": [...]}`.** An envelope earns its place when it
carries metadata; there is no pagination on plans and no plan count worth
sending. The stretch `GET /purchases` *does* need `has_more`, and will have an
envelope — the two differ because their requirements differ, not from
inconsistency, and that is the defence if challenged.

**`remaining_slots` is composed here, not stored.** §2.2 put inventory in a
separate structure, so the assembler reads `inventory.remaining(plan.id())` and
maps `OptionalInt.empty() → null`. This is the single place in the system where
"unlimited inventory" becomes a JSON null, which is exactly where it belongs:
at the edge, in the wire format, not in the domain.

**`credits` and `remaining_slots` are both `5` for Launch Promo Pack** and come
from two different structures. §2.6 explains why that coincidence is harmless.

### 3.3 `POST /v1/memberships/purchases`

**Request:**

```json
{ "member_name": "Alice",
  "plan_id": "11111111-1111-1111-1111-111111111111",
  "start_date": "2026-11-01" }
```

**`201 Created`**, with a `Location` header:

```
Location: /v1/memberships/purchases/{id}
```

```json
{
  "id": "9f1c...",
  "member_name": "Alice",
  "plan": {
    "plan_id": "11111111-1111-1111-1111-111111111111",
    "name": "Monthly Unlimited",
    "type": "unlimited",
    "price_cents": 8000,
    "credits": null
  },
  "amount_cents": 8000,
  "status": "active",
  "start_date": "2026-11-01",
  "transaction_id": "txn-6b2e...",
  "created_at": "2026-10-30T14:05:11Z"
}
```

**`plan` is nested, not flattened.** PRD D-7 made this a *snapshot* — a
historical record of what was sold. Nesting makes that visible: these five fields
travel together and describe the plan as it was at purchase time, which a flat
`plan_name` / `plan_price_cents` sprawl obscures.

**`amount_cents` is repeated outside the snapshot** even though it equals
`plan.price_cents` today. They are different facts: one is what the plan cost,
the other is what this member was charged. A discount, proration, or tax would
separate them, and FR-6 asks for "the amount charged" as a first-class field.

**`Location` on a `201`** because the resource is addressable and we have just
been told its id — PRD FR-4 exists precisely so that URL resolves. Cheap, correct
HTTP, and a reasonable thing to be asked about.

**`member_name` echoes what was submitted**, not the normalised key (§2.4). The
`MemberKey` never appears on the wire; it is an internal matching concern.

### 3.4 `GET /v1/memberships/purchases/{id}`

`200 OK` with the identical payload shape as §3.3 — one assembler, one DTO, so
the two endpoints cannot drift.

The `400`-vs-`404` boundary, which is a real distinction and worth defending:

| Request | Status | `code` | Reasoning |
|---|---|---|---|
| `/purchases/not-a-uuid` | `400` | `validation_failed` | The id is not a well-formed identifier. The client sent something that could never name a purchase |
| `/purchases/{valid-but-unknown-uuid}` | `404` | `purchase_not_found` | A well-formed id that names nothing. The client asked a sensible question with a negative answer |

Mechanically, `@PathVariable UUID id` makes Spring throw
`MethodArgumentTypeMismatchException` before the controller body runs. Spring's
default renders a bare `400`, which loses the `code`, so §3.6's advice handles
that exception explicitly.

### 3.5 Problem Details shape

`Content-Type: application/problem+json`, per D-3.

```json
{
  "type": "urn:glofox:problem:plan-sold-out",
  "title": "Plan sold out",
  "status": 409,
  "detail": "Launch Promo Pack has no remaining slots.",
  "instance": "/v1/memberships/purchases",
  "code": "plan_sold_out"
}
```

Spring's `ProblemDetail` provides `type`, `title`, `status`, `detail`,
`instance`; `code` is attached via `setProperty("code", ...)`, which the
serialiser flattens to a top-level member as RFC 9457 extensions require.

**`type` is a URN, not an `https://` URL.** The RFC says `type` SHOULD
dereference to documentation. Inventing `https://glofox.example/problems/...`
promises a page that does not exist, and `about:blank` discards the identity
entirely. A URN is a valid URI that makes no dereferenceability promise and
stays stable.

**On the redundancy between `type` and `code` — stated rather than hidden.** A
purist would use only `type`, and they would have a point. `code` exists because
a short stable token is what clients actually switch on and what greps cleanly in
logs; comparing URI strings in a `switch` is worse at every call site. The
redundancy is a deliberate ergonomics trade, and `code` is the field documented
as the contract.

**Validation errors carry a field-level extension**, since one `detail` string
cannot describe three bad fields:

```json
{
  "type": "urn:glofox:problem:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request contains 2 invalid fields.",
  "instance": "/v1/memberships/purchases",
  "code": "validation_failed",
  "errors": [
    { "field": "member_name", "message": "must not be blank" },
    { "field": "start_date",  "message": "must be a calendar date (YYYY-MM-DD)" }
  ]
}
```

`errors` appears only on `validation_failed`. Every other problem concerns the
request as a whole, not a field of it.

### 3.6 Exception-to-status mapping

**Domain exceptions are plain and HTTP-free.** No `HttpStatus`, no
`@ResponseStatus`, nothing from `org.springframework.http` appears on them — the
same NFR-4 argument as §3.1, applied to the error path. A `DomainException` base
carrying its own status would be more compact and would put a web type in the
domain, so it is rejected.

**The web layer owns one table, in one `@RestControllerAdvice`:**

```java
private static final Map<Class<? extends Exception>, Problem> MAPPINGS = Map.of(
    PlanNotFoundException.class,              problem(NOT_FOUND,   "plan_not_found"),
    PurchaseNotFoundException.class,          problem(NOT_FOUND,   "purchase_not_found"),
    PlanInactiveException.class,              problem(CONFLICT,    "plan_inactive"),
    UnlimitedMembershipExistsException.class, problem(CONFLICT,    "unlimited_membership_exists"),
    PlanSoldOutException.class,               problem(CONFLICT,    "plan_sold_out"),
    StartDateInPastException.class,           problem(BAD_REQUEST, "start_date_in_past"),
    PaymentDeclinedException.class,           problem(PAYMENT_REQUIRED, "payment_declined"),
    PaymentGatewayUnavailableException.class, problem(BAD_GATEWAY, "payment_gateway_error"));
```

One handler performs the lookup; anything absent falls through to `500`
`internal_error` with the cause logged and no detail leaked to the client.

**The table is isomorphic to PRD AC-10 by design.** The specification table and
the code table have the same rows in the same order, so a reviewer can diff them
by eye and a new error is one row in each. That is the concrete answer to
"where does the HTTP mapping live" — in exactly one place, in the web layer.

**Three handlers sit outside the table**, because they translate framework
exceptions rather than domain ones:

| Exception | → | Note |
|---|---|---|
| `MethodArgumentTypeMismatchException` | `400 validation_failed` | Non-UUID path variable (§3.4) |
| Jackson parse / `DateTimeParseException` | `400 validation_failed` | Malformed body or date; populates `errors` |
| Bean-validation or hand-rolled failures | `400 validation_failed` | Mechanism decided in §1.5 |

**`PaymentFailedException` is unchecked, so the table is what stops it becoming a
`500`.** This is the trap `codebase-context.md` constraint 1 flags: nothing in
the compiler forces anyone to handle it. Covering it by class in the mapping is
what makes PRD AC-8a/8b observable rather than aspirational — and §1.7 must
produce two distinct types for these two rows to exist.

**The advice only translates.** §1.4.2's compensation stack has already unwound
inventory and claims inside the service before the exception reaches here. The
web layer never touches inventory, which is NFR-6 in one sentence.

---

## 4. Edge Cases

> **This section is an audit of §1–3, not new design.** Its job is to find gaps
> while they are still cheap. It found four that change earlier sections — F-1
> through F-4, collected in §6.

### 4.1 `remaining_slots: null` versus `0`

§2.3 removed the bug class structurally: no entry means unlimited, so there is
no `remaining > 0` to invert. What remains is a boundary table and one rule that
must not be broken later.

| State | `tryReserve` | `GET /v1/plans` shows |
|---|---|---|
| No entry (unlimited inventory) | `true`, always | `null` |
| Entry, value `> 0` | `true`, decrements | the number |
| Entry, value `0` | `false` → `409 plan_sold_out` | `0` |

`0` and `null` are therefore **distinguishable on the wire**, which they must be:
`0` means "this offer is finite and gone", `null` means "no limit applies". A
client showing "5 left" cannot conflate them.

**The rule that protects this:** `remaining()` returns `OptionalInt` and **no
`int`-returning overload is ever added.** A convenience method returning `0` for
unlimited would reintroduce PRD T-4 in one line, at a call site far from here.
The seed mapping must also not special-case `0` as unlimited — a plan seeded
with `remainingSlots = 0` is legitimately sold out from boot.

### 4.2 Unchecked exception escape paths

`PaymentFailedException extends RuntimeException`, so the compiler enforces
nothing (`codebase-context.md` constraint 1). Every escape route, audited:

| Path | Outcome | Verdict |
|---|---|---|
| Thrown by `charge` in step 6 | Caught by the service unwind, rethrown, mapped by §3.6 | ✅ closed |
| A *new* payment subtype added later | Caught only if the service catches `RuntimeException`, not the payment type | ✅ closed by §4.9's single catch |
| Gateway throws something else (NPE from a buggy impl, wrapped timeout) | Unwind still runs; not in the §3.6 table → `500 internal_error` | ✅ correct — an unexpected throwable is our bug, not an outage |
| **The unwind itself throws** | Original exception lost; inventory partly unreleased; client gets a misleading `500` | ⚠️ **F-1 — not yet closed** |
| Response serialisation fails after `201` is committed | Status already sent; advice cannot help | ⚠️ accepted, negligible with records and fixed DTOs |
| `Error` (OOM, etc.) | Not caught | ✅ correct, nothing useful to do |

**F-1 — the unwind must not be able to throw.** §1.4.2 says "unwind claims in
reverse, rethrow", which as written has a hole: if `release()` fails on the first
claim, later claims leak *and* the original cause disappears. Required shape:

```java
catch (RuntimeException original) {
    for (Runnable release : reversed(claims)) {
        try { release.run(); }
        catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);   // keep the real cause
            log.error("compensation failed", cleanupFailure);
        }
    }
    throw original;
}
```

Every release is attempted, failures are suppressed onto the original rather
than replacing it, and the client still receives the accurate error.

### 4.3 Partial-failure windows

Enumerated against §1.4.2's eight steps. The framing that matters:

> **The payment gateway is the only durable thing in this system.** Storage is
> in-memory, so a crash erases our records — but not the charge. Every window
> after step 6 is therefore not merely "lost state" but "money taken, with no
> record that it was, and no record left to reconcile from."

| Dies after | Inventory | Claim | Purchase | Money | Observable |
|---|---|---|---|---|---|
| 1–3 | untouched | untouched | none | none | nothing happened |
| 4 (claim taken) | untouched | **held** | none | none | in-process: leaked claim (W-3). On crash: erased with everything |
| 5 (slot reserved) | **held** | held | none | none | same — W-3 |
| 6 (charged) | held | held | none | **taken** | **W-2. Unrecoverable** |
| 7 (saved) | decremented | held | exists | taken | consistent — the intended end state |

W-1 (the transient dip) is not a crash window but a *concurrency* window: while
step 6 is in flight, another caller's `GET /v1/plans` sees the reserved slot
missing. Accepted in §1.4.6; the end state is what AC-8 constrains.

Rows 4 and 5 differ in-process (a live leaked claim) from on-crash (nothing
survives). W-3's mitigation — reservation TTLs — matters only for the in-process
case, and is out of scope.

### 4.4 Concurrent same-member unlimited

`putIfAbsent` returns `null` to exactly one thread. The loser never reaches the
gateway, so no money moves on the rejected path.

| Scenario | Contend? | Result |
|---|---|---|
| Same member, same unlimited plan, ×2 | **yes** | one `201`, one `409 unlimited_membership_exists` |
| Same member, **two different** unlimited plans | **yes** | one succeeds — correct, see below |
| Different members, same plan | no | both `201`; different keys never collide |
| Same member, unlimited + pack | no | both `201`; packs never touch the claim store (D-5) |
| Same member, two packs | no | both `201` |

**The claim is keyed on the member alone, not on `(member, planId)`.** This is
load-bearing. FR-5(d) says "at most one active *unlimited* membership", not one
per plan. Keyed by `(member, planId)`, a member could hold Monthly Unlimited and
Legacy Plan simultaneously, satisfying both keys while violating the rule. The
key *is* the requirement.

**Accepted transient false rejection.** The loser is told
`unlimited_membership_exists` while the winner's charge is still in flight. If
that charge fails, the winner's claim is released and nobody holds an unlimited
membership — so the loser was rejected on a membership that never existed. A
retry succeeds. This is W-1's logic applied to claims, and the trade is the
same: a transient false "no" is recoverable, a double-sold entitlement is not.

### 4.5 Validation ordering

Why PRD FR-3's order is the order:

| Before | After | Because |
|---|---|---|
| Shape | Semantics | Cannot test "blank after trim" on a field that is absent |
| Resolution (404) | Plan state (409) | Cannot call a plan "inactive" when it does not exist |
| Member state | Inventory | Avoids reserving a slot the next check would immediately release — a spurious W-1 dip |
| Everything | Payment | Never call the gateway for a request we were going to reject |

**Honesty about the third row:** in the seeded data every unlimited plan has
`remainingSlots = null`, so no plan is subject to both checks. That ordering is
defensive, not currently exercised — worth saying rather than claiming a benefit
no seeded plan can demonstrate.

**F-2 — batched field errors and `start_date_in_past` were never reconciled.**
§3.5 gives validation failures an `errors` array under code `validation_failed`,
while PRD AC-11 gives past dates their own code `start_date_in_past`. Those two
cannot both describe one response. The rule:

- **Step 1, shape** — collect **all** field failures, respond
  `400 validation_failed` with `errors` populated. A client fixing a malformed
  body should see every problem at once.
- **Step 2, semantics** — ordered checks, **fail fast** with the specific code:
  `start_date_in_past`, or `validation_failed` for a blank name. No `errors`
  array.

Cost: a request with both a blank name and a past date needs two round trips.
Accepted — there are exactly two semantic checks, and the specific code is worth
more to a client than batching two rare cases.

### 4.6 Cancel and inventory restore (Tier 2)

| Case | Behaviour |
|---|---|
| Cancel `ACTIVE` purchase, finite-inventory plan | status → `CANCELLED`, slot restored |
| Cancel `ACTIVE` purchase, unlimited-inventory plan | status → `CANCELLED`, nothing to restore |
| Cancel an `ACTIVE` **unlimited** membership | claim released via `release(key, purchaseId)`; D-9 frees the member |
| Cancel an already-`CANCELLED` purchase | rejected — see F-4 |
| Cancel an unknown id | `404 purchase_not_found` |

**F-3 — concurrent double-cancel over-credits inventory, and §2.5 cannot prevent
it.** Two simultaneous cancels of the same purchase both read `status = ACTIVE`,
both pass the guard, and both restore a slot — leaving a 5-slot pack with 6. This
is the *mirror* of NFR-1's race, on the very path built to undo it, and
`PurchaseStore` as specified in §2.5 has only `save` and `findById`, so
read-then-write is all it can express.

The status transition and the restore must be one atomic decision:

```java
// §2.5 gains:
boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next);
```

Only the thread that wins the CAS restores inventory and releases the claim.
Maps to `ConcurrentHashMap.replace(key, oldValue, newValue)` in memory, and to
`UPDATE purchases SET status='CANCELLED' WHERE id=? AND status='ACTIVE'` with
rows-affected = 1 in SQL — the same shape as every other claim in §1.4.7.

**F-4 — a second cancel needs a code PRD AC-10 does not have.** Losing the CAS
means already cancelled. Options were a silent idempotent `200` or an explicit
conflict; the call is `409 purchase_already_cancelled`, because a client
cancelling twice has a bug and hiding it helps nobody. Needs a PRD amendment,
and only if Tier 2 is reached.

### 4.7 Retry and double-charge (Tier 3)

`charge(int, String)` carries no idempotency key, so deduplication lives in the
service as a third claim: `ConcurrentHashMap<String, UUID>` from
`Idempotency-Key` to purchase id, claimed by `putIfAbsent` **before** step 6.

That it slots into §1.4.2's compensation stack as simply a third `push` is a
genuine validation of that design — the pattern scaled without changing shape.

**But the stack's uniform "release everything on failure" rule breaks here, and
this is why Tier 3 is ranked last.** The dedupe key must be released if we fail
*before* charging, and must **not** be released if we fail *after* charging —
releasing it would let a retry charge the card a second time, the exact harm the
feature exists to prevent. So the stack needs two kinds of entry:

| Claim | Release on pre-charge failure | Release on post-charge failure |
|---|---|---|
| Inventory slot | yes | yes |
| Unlimited claim | yes | yes |
| **Idempotency key** | yes | **no** |

A uniform stack cannot express that. Tier 3 therefore *changes* §1.4.2's
compensation model rather than extending it — a structural change at the end of
the timebox, on the code path that handles money. Confirmed as last, gated on
Tier 2 green with ≥1h remaining.

### 4.8 The `start_date` today boundary (Q-1)

| `start_date` | Result |
|---|---|
| yesterday | `400 start_date_in_past` |
| **today** | `201` — today is valid; the check is *strictly* before |
| tomorrow | `201` |
| far future (2099) | `201` — no upper bound (AS-5) |
| malformed (`2026-13-01`, `not-a-date`) | `400 validation_failed` — step 1, not step 2 |

**The clock is read once per request**, not once per check. Two reads could
straddle midnight and disagree with each other, making a single request
internally inconsistent — a bug that would reproduce roughly once a day.

**Midnight crossing is accepted.** A request arriving at `23:59:59.9` and
validated at `00:00:00.1` has a `start_date` of the former "today" rejected as
past. Transient, rare, recoverable by retry, and inherent to AS-8's
server-timezone definition of "today".

### 4.9 Cleanup parity across both payment failure modes (Q-3)

Before Q-3 there was one payment failure path carrying the no-purchase,
no-inventory-change guarantee. There are now two, and **the second is where that
guarantee gets silently dropped — because the first one's test still passes.**

The structural answer is that there is still only one cleanup path. The service
catches `RuntimeException`, not a payment type:

```java
catch (RuntimeException original) { unwind(claims, original); throw original; }
```

This is deliberate, for a reason §1.7 has not yet decided. If §1.7 makes the two
payment types **siblings** rather than both subtypes of `PaymentFailedException`,
then `catch (PaymentFailedException ...)` would miss one entirely — a `502` that
silently keeps a promo slot. Catching `RuntimeException` is correct under *either*
§1.7 outcome, which is why §4.2's audit chose it.

**The requirement §1.7 inherits:** whatever it produces must give §3.6 two
distinct classes to key its table on, and must not require the service to
enumerate payment types in order to clean up correctly.

**T-2 and T-10 assert identical post-state** — not merely "an error was
returned". Same purchase-absent assertion, same inventory-unchanged assertion,
differing only in status and `code`. Written as one parameterised test over the
two failure modes, so a future third mode cannot be added without a cleanup
assertion arriving with it.

---

## 5. Test Strategy

"Which tests you chose and why" is a stated evaluation criterion, so the
*reasoning* here is part of the deliverable, not scaffolding around it.

### 5.1 Test levels

**The level is chosen by where the risk lives, never for symmetry.**

| Level | Cost | Right for |
|---|---|---|
| Plain JUnit on the service, with **real** store implementations | ~ms, no Spring context | concurrency, business rules, compensation |
| `@WebMvcTest` slice, service mocked | ~1s context | status mapping, JSON shape, request binding |
| `@SpringBootTest` full context | seconds | wiring, bean overrides, one end-to-end path |

**The critical call: concurrency tests use the real in-memory stores, not
mocks.** The atomicity being tested *lives in the store* (§1.4.4) — mocking
`InventoryStore` would mock away the system under test and leave a green suite
proving nothing. This is the single most important choice in this section, and
the one most likely to be got wrong by reflex.

| Risk | Lives in | Level |
|---|---|---|
| T-1, T-3, T-12 — races | service + real store | plain JUnit |
| T-2, T-10 — failure cleanup | service + real store + stub gateway | plain JUnit |
| T-4, T-5, T-9, T-11 — rules | service + real store | plain JUnit |
| T-7 — validation and error bodies | HTTP edge | `@WebMvcTest` |
| T-8 — plan payload incl. inactive | HTTP edge | `@WebMvcTest` |
| T-6 — round-trip, plus one `402` end-to-end | wiring | `@SpringBootTest` |

**A Boot-specific cost worth knowing:** each distinct combination of
`@MockitoBean` / `@TestConfiguration` produces a **separate application
context**, and the context cache misses on every new combination. Scattering bean
overrides across six classes means six context startups. Grouping full-context
tests into one class with one override set keeps the suite seconds rather than
tens of seconds — which matters when the whole budget is three hours.

### 5.2 Gateway substitution

`@MockBean` is **removed in Boot 4** (`codebase-context.md` constraint 5). But
the more useful observation is that most of these tests need no framework at all:

> **The port exists so that substitution is constructor injection, not an
> annotation.** Reaching for `@MockitoBean` to replace a dependency the service
> already takes as a parameter is using Spring to solve a problem plain Java
> solved. Service-level tests pass a stub directly.

| Need | Mechanism |
|---|---|
| Service-level failure tests (most of them) | hand-written stub passed to the constructor |
| The one full-context test proving the override wires | `@MockitoBean PaymentGateway` |

**Hand-written stubs beat Mockito mocks here, because they can count:**

```java
final class CountingGateway implements PaymentGateway {
    final AtomicInteger charges = new AtomicInteger();
    private final Supplier<PaymentResult> behaviour;

    public PaymentResult charge(int amountCents, String memberName) {
        charges.incrementAndGet();
        return behaviour.get();        // succeed, or throw decline / unavailable
    }
}
```

**This counter catches a bug no outcome assertion can.** T-1 as stated in the
PRD asserts five `201`s and a final counter of `0`. **A implementation that
charged all twenty cards and persisted only five would pass both.** So T-1 must
additionally assert `charges.get() == 5`: exactly five cards were charged, not
twenty with fifteen silently abandoned. Added to the harness below.

### 5.3 Concurrency harness

```java
int attempts = 500;                         // 5 slots, 500 contenders
var startGate = new CountDownLatch(1);
var results   = new ConcurrentLinkedQueue<Outcome>();

try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {   // Java 21
    for (int i = 0; i < attempts; i++) {
        pool.submit(() -> {
            startGate.await();              // all released together
            results.add(attemptPurchase());
        });
    }
    startGate.countDown();
}                                            // close() awaits termination
```

**Virtual threads, not a fixed platform pool.** Java 21 makes 500 concurrent
attempts cheap, where 500 OS threads would not be. More contenders on five slots
means a far better chance of hitting a narrow interleaving, which is the whole
point. The toolchain is already on 21 — using it is free.

**No `Thread.sleep` anywhere.** A start gate releases every thread at once;
`close()` on the try-with-resources executor awaits termination. Sleeps are both
slower and less reliable than a latch.

**The assertions that make NFR-1 meaningful** — all four, because each catches a
different wrong implementation:

| Assert | Catches |
|---|---|
| exactly 5 × `201` | overselling |
| every other result is `409 plan_sold_out` | wrong error for the losers |
| `remaining_slots == 0` at the end | a counter that drifted below zero or never moved |
| `gateway.charges.get() == 5` | charging everyone and keeping five |

**The anti-pattern this section is written against:** a concurrency test that
passes because the race window was never hit. Mitigated three ways — the start
gate maximises interleaving, 500 contenders widen the odds, and **every
assertion is on an invariant** rather than on an observed schedule, so even a
fully serialised run still verifies correctness. `@RepeatedTest(10)` is the
hardening option if time allows; one run at 500 contenders is the baseline.

Each test constructs fresh stores, so repeated runs share no state.

### 5.4 Clock control in tests

```java
Clock fixed = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
```

**The zone is pinned as well as the instant.** "Today" is
`LocalDate.now(clock)`, so a fixed instant with a floating zone is still
non-deterministic. AS-8 defines "today" by the server zone, which means tests
must state one.

**The payoff from §1.6 is observable right here:** T-1, T-3, T-4, T-5, T-11 and
T-12 take `Clock.systemUTC()` and a far-future `start_date`, and need no clock
control whatsoever. Only T-9 pins a clock. Had the active-unlimited rule been
date-windowed (the D-2 alternative), every concurrency test would have needed a
controlled clock — turning the hardest tests in the suite into the most fragile.

**The one gap a test cannot close:** nothing stops a future `LocalDate.now()`
with no argument from appearing in the domain. That is a code-review obligation,
or an ArchUnit rule if a dependency were worth it here. Named rather than
pretended away.

### 5.5 Coverage map

Six classes. Three contexts total (two slices, one full), so the context cache
is used rather than thrashed.

| Class | Level | Tests |
|---|---|---|
| `PurchaseServiceConcurrencyTest` | plain JUnit | T-1 oversell, T-3 duplicate unlimited, T-12 double cancel |
| `PurchaseServicePaymentFailureTest` | plain JUnit | T-2 decline, T-10 outage — **one parameterised test over both modes** |
| `PurchaseServiceRulesTest` | plain JUnit | T-4 null inventory, T-5 inactive plan, T-9 date boundary, T-11 cancel-then-repurchase |
| `PurchaseControllerValidationTest` | `@WebMvcTest` | T-7 validation matrix and error bodies |
| `PlanControllerTest` | `@WebMvcTest` | T-8 four plans incl. inactive, null vs 0 |
| `PurchaseApiIntegrationTest` | `@SpringBootTest` | T-6 round-trip and `404`, plus one `402` proving the override wires |

**T-2 and T-10 are deliberately one parameterised test**, not two. §4.9's whole
point is that the second failure mode is where the cleanup guarantee gets
dropped; parameterising means a future third mode cannot be added without its
cleanup assertions coming along.

### 5.6 Deliberately untested

Answering evaluation criterion #4 directly: coverage is not the goal, retired
risk is.

| Not tested | Why |
|---|---|
| W-2 charged-but-not-persisted | Needs a crash between two statements. Untestable in-process without instrumenting production code, which would be worse than the risk |
| W-3 reservation leak | Requires killing a thread mid-flight; the mitigation (TTLs) is out of scope anyway |
| Cross-instance oversell (AS-4) | Single process by assignment; a test would assert a known non-goal |
| Midnight-crossing rejection (§4.8) | Needs the clock to advance *during* one request. Cost far exceeds a rare, retryable transient |
| Response-serialisation failure after `201` | Status already committed; no behaviour to assert |
| Throughput / latency | Not a requirement. NFR-1 is about correctness under concurrency, not speed |
| Byte-exact JSON snapshots | Brittle against field ordering, and T-7/T-8 already assert the fields that are contractual |
| Tier 3 idempotency | Only if reached |
| Getters, records, enum round-trips | Testing the compiler |

**One borderline case, declared rather than quietly dropped:** the
`Locale.ROOT` fix in §2.4 defends against a *default-locale* bug, so
`new MemberKey("ALICE").equals(new MemberKey("alice"))` passes whether or not the
fix is present. Genuinely testing it means setting the JVM default locale to
Turkish and restoring it — global mutable state in a test, with leakage risk
across a parallel suite. Declined for a three-hour exercise; the reasoning is
recorded in §2.4 where a reviewer will find it, and it is an honest answer if
asked "how do you know that line is right?"

---

## 6. Risks and Deferred Items

Carried from PRD §13 and `STATE.md`, plus anything surfaced while expanding the
sections above. Closed last.

### Findings from the §4 audit

**All four applied** (approved 2026-10-30). Retained here as the audit trail —
each one amended a section that had already been signed off, which is what the
audit section exists to catch.

| # | Finding | Applied to | Status |
|---|---|---|---|
| **F-1** | The compensation unwind can itself throw, losing the original cause and leaking later claims. Needs per-release `try`/`catch` with `addSuppressed` | §1.4.2 | ✅ applied |
| **F-2** | §3.5's batched `errors` array and AC-11's `start_date_in_past` code cannot both describe one response. Resolved: batch shape errors, fail fast on semantics | §4.5 | ✅ applied |
| **F-3** | Concurrent double-cancel over-credits inventory. `PurchaseStore` needs `compareAndSetStatus` — read-then-write cannot express it | §2.5 | ✅ applied *(Tier 2)* |
| **F-4** | A second cancel needs `409 purchase_already_cancelled` | PRD AC-10 → D-10, T-12 | ✅ applied *(Tier 2)* |
| **F-5** | PRD T-1's assertions were satisfiable by a wrong implementation: charging all contenders and persisting only five yields 5 × `201` and a final counter of `0`. Needs a fourth assertion on the gateway's charge count | §5.2, §5.3 → PRD T-1 | ✅ applied *(new, from §5)* |

F-1 was the only one affecting committed Tier 0/1 scope. F-3 and F-4 are inert
unless Tier 2 is reached, but folding them in now cost minutes where discovering
them at hour three would have cost the tier.

**F-3 also added PRD T-12**, which is the test that matters: T-11 (cancel then
repurchase) passes even with the F-3 bug present, because it never cancels twice.
Only asserting the counter's final value after two concurrent cancels catches it.

### Deferred by design

| Item | Why deferred | Named in |
|---|---|---|
| W-2: charged-but-not-persisted | Uncloseable without an idempotency key, outbox, or reconciliation job | §1.4.6, §4.3 |
| W-3: reservation leak on thread death | Needs reservation TTLs | §1.4.6, §4.3 |
| Cross-instance correctness | In-process only; a second instance would oversell | PRD AS-4 |
| Midnight-crossing date rejection | Inherent to a server-timezone "today" | §4.8, PRD AS-8 |
| Response-serialisation failure after `201` | Status already committed | §4.2 |
| Tier 3 compensation-model change | Idempotency keys break the uniform release rule | §4.7 |

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
LAST APPROVED: §4 edge cases; F-1/F-3/F-4 applied
NEXT STEP: Review §5 (test strategy) and F-5. Then only §1.1-1.3 and
           §1.5-1.8 remain -- §1.7 owed to §3.6 and §4.9.
==================================================
```
