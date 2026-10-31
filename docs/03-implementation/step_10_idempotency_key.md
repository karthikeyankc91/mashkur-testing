# Step 10 — `Idempotency-Key` on purchase (Tier 3)

## 1. Context & Objective

An optional `Idempotency-Key` request header makes `POST /purchases` safe to
retry: the same key with the same payload returns the original purchase instead
of charging a second time.

**Why this was deliberately scheduled last.** The payment port takes no
idempotency parameter, so deduplication cannot be delegated to the gateway — it
has to live in the service, where it introduces a *second* race that interacts
with the inventory race already there. It is the riskiest change in the
exercise, which is why core shipped and merged first.

**Objective:** a replayed request charges once; concurrent requests with one key
do not both proceed; the same key with a different payload is refused.

## 2. The insight that makes this cheap rather than dangerous

> **An idempotency key is the same claim-or-fail pattern as the promo counter
> and the one-unlimited rule.** Claim the key atomically, do the work, and
> release the claim if anything fails.

So the key claim becomes **compensation entry #0** on the stack `purchase()`
already maintains, reusing the existing unwind — including its `addSuppressed`
handling — unchanged. No new concurrency mechanism is invented. This is the
third instance of one pattern, not a third pattern.

That also fixes the subtle failure mode for free: **if the charge fails, the key
must be released**, or an honest client retrying after a declined card would be
told its request is already in progress forever.

## 3. Files

```
src/main/java/com/glofox/memberships/
├── store/IdempotencyStore.java                      CREATE  (port + claim result)
├── storage/memory/InMemoryIdempotencyStore.java     CREATE
├── service/
│   ├── PurchaseService.java                         MODIFY  (4-arg overload)
│   ├── PurchaseOutcome.java                         CREATE
│   ├── IdempotentRequestInProgressException.java    CREATE
│   └── IdempotencyKeyReuseException.java            CREATE
├── config/SeedingConfig.java                        MODIFY  (one bean)
└── web/
    ├── PurchaseController.java                      MODIFY  (read the header)
    └── ApiExceptionHandler.java                     MODIFY  (rows 12 and 13)

src/test/java/com/glofox/memberships/
├── TestStores.java                                  MODIFY
└── service/PurchaseIdempotencyTest.java             CREATE
```

## 4. Design

### 4.1 The claim result is a sealed interface, not a boolean

A boolean cannot express four distinct answers, and conflating any two of them
is a bug:

```java
public sealed interface Claim {
    record Acquired()                     implements Claim { }  // proceed
    record InProgress()                   implements Claim { }  // 409, retry later
    record Replay(UUID purchaseId)        implements Claim { }  // return the original
    record Conflict()                     implements Claim { }  // 409, key reused
}

Claim claim(String key, String fingerprint);
void  complete(String key, UUID purchaseId);
void  release(String key);
```

Sealed, so `switch` over it is exhaustive and a future fifth state cannot be
silently unhandled.

### 4.2 The store, and why `putIfAbsent` again

```java
private record Record(String fingerprint, UUID purchaseId) { }   // purchaseId null == in flight

public Claim claim(String key, String fingerprint) {
    Record existing = records.putIfAbsent(key, new Record(fingerprint, null));
    if (existing == null)                                return new Acquired();
    if (!existing.fingerprint().equals(fingerprint))     return new Conflict();
    return existing.purchaseId() == null
            ? new InProgress()
            : new Replay(existing.purchaseId());
}
```

**The fingerprint is checked before the in-flight test**, deliberately: a key
reused with a different body is a client bug worth reporting even while the
first request is still running.

`complete` uses `replace(key, old, new)`; `release` uses `remove(key, value)` —
the same value-safe two-argument forms as the claim store, for the same reason.

### 4.3 The fingerprint is a hash, not the payload

`SHA-256` over `memberName|planId|startDate`, hex-encoded.

Storing the raw name would retain an unbounded caller-controlled string a
*fourth* time, which is the heap-exhaustion concern the 100-character bound
addresses. A 64-character digest is fixed-size regardless of input.

### 4.4 Replay returns `201`, with a header

A replay returns the **original status and body**, plus
`Idempotency-Replayed: true`. Returning `200` instead would be defensible, but
then a client could not tell "your retry worked" from "you fetched something" —
and the whole point is that a retry is indistinguishable from the first call
except for the header.

The service returns `PurchaseOutcome(Purchase, boolean replayed)` from a 4-arg
overload; the existing 3-arg `purchase()` is untouched, so every core test and
caller is unaffected.

### 4.5 Two new error rows (12 and 13)

| Status | `code` | When |
|---|---|---|
| `409` | `idempotent_request_in_progress` | the same key is mid-flight |
| `409` | `idempotency_key_reuse` | the same key, a different payload |

This extends the error contract beyond the PRD table; recorded here and in
`SUBMISSION.md` rather than reopening the design phase.

## 5. Tests — `PurchaseIdempotencyTest`

| Test | Asserts |
|---|---|
| `absentKeyBehavesExactlyAsBefore` | no header → two purchases, two charges |
| `sameKeyReplaysTheOriginalPurchase` | same id returned, **charges() == 1** |
| `replayIsFlagged` | second call reports `replayed == true` |
| `differentKeysAreIndependent` | two purchases, two charges |
| `reusedKeyWithDifferentBodyIsRefused` | `IdempotencyKeyReuseException` |
| `failedChargeReleasesTheKey` | decline, then a retry with the same key **succeeds** |
| **`concurrentSameKeyChargesOnce`** | **500 concurrent requests, one key → `charges() == 1`, and every non-winner is either a replay or in-progress — never a second purchase** |
| `keyDoesNotBypassBusinessRules` | a key does not let a member hold two unlimited memberships |

`concurrentSameKeyChargesOnce` is the headline: the charge count is the only
assertion that catches "deduplicated the response but charged twice".

## 6. Verification

```bash
./gradlew test --tests '*PurchaseIdempotencyTest' --console=plain
./gradlew test --console=plain
grep -c "entry(" src/main/java/com/glofox/memberships/web/ApiExceptionHandler.java   # expect 13

# live: same key twice -> 201 then 201 with the replay header, ONE purchase
curl -i -X POST .../purchases -H 'Idempotency-Key: k1' -d '{...}'
curl -i -X POST .../purchases -H 'Idempotency-Key: k1' -d '{...}'   # Idempotency-Replayed: true
curl -i -X POST .../purchases -H 'Idempotency-Key: k1' -d '{changed}'  # 409 idempotency_key_reuse
```

## 7. Known limitation, stated rather than hidden

**Keys are never evicted.** The store grows without bound, which is the same
in-memory-storage problem as everywhere else in this exercise, now with a
caller-controlled key. A real implementation needs a TTL — Stripe uses 24 hours.
Recorded in `SUBMISSION.md`.
