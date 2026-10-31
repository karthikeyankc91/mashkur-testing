# Step 09 — Cancel with inventory restore (Tier 2)

## 1. Context & Objective

`POST /v1/memberships/purchases/{id}/cancel` soft-cancels a purchase and returns
what it claimed: a promo slot, and the member's unlimited slot.

This step exists because the cancel path is the **mirror** of the purchase path,
and the mirror has its own race that the purchase path does not. Everything it
needs was designed in step 01 for exactly this moment —
`PurchaseStore.compareAndSetStatus`, `Purchase.withStatus`, and
`PurchaseStatus.CANCELLED`, none of which core scope uses.

**Objective:** cancel works, double-cancel is refused, and a cancelled promo
purchase returns its slot exactly once.

**Scope note:** this is PRD Tier 2, gated on core being green and merged. It is.

## 2. Dependencies

| Needs | Status |
|---|---|
| Core scope complete and merged | yes, milestone 3 |
| `compareAndSetStatus` on the store | yes, step 02 |
| `Purchase.withStatus` | yes, step 01 |
| `purchase_already_cancelled` in the error table | yes, reserved in the PRD |

## 3. Files

```
src/main/java/com/glofox/memberships/
├── service/
│   ├── PurchaseService.java                    MODIFY  (add cancel)
│   └── PurchaseAlreadyCancelledException.java  CREATE
└── web/
    ├── PurchaseController.java                 MODIFY  (add the endpoint)
    └── ApiExceptionHandler.java                MODIFY  (the ELEVENTH table row)

src/test/java/com/glofox/memberships/service/
└── PurchaseCancelTest.java                     CREATE
```

> **The eleventh row is why step 06 used `Map.ofEntries`.** `Map.of` caps at ten
> pairs and the table had exactly ten. This row would not have compiled.

## 4. Step-by-step

### 4.1 The ordering decision — CAS first, then restore

This is the entire substance of the step.

```java
public Purchase cancel(UUID purchaseId) {
    Purchase purchase = purchases.findById(purchaseId)
            .orElseThrow(() -> new PurchaseNotFoundException(purchaseId));

    // THE GATE. Exactly one caller can win this, and only the winner
    // compensates.
    if (!purchases.compareAndSetStatus(purchaseId, ACTIVE, CANCELLED)) {
        throw new PurchaseAlreadyCancelledException(purchaseId);
    }

    // Reached only by the winner, so each of these runs exactly once.
    if (purchase.plan().type() == PlanType.UNLIMITED) {
        releaseClaim(purchase.memberKey(), purchaseId);
    }
    inventory.release(purchase.plan().planId());   // no-op when unlimited

    return purchases.findById(purchaseId).orElseThrow(() -> new PurchaseNotFoundException(purchaseId));
}
```

**Why the order is not negotiable.** Restore-then-CAS looks equivalent and is
not. Two concurrent cancels of the same purchase would both read `ACTIVE`, both
restore the promo slot, and only then would one lose the CAS — leaving the
counter one *above* where it should be. **That oversells the pack through the
cancel endpoint**: six people end up holding a five-slot promo.

The compare-and-set is what *elects* the single caller entitled to compensate.
It is the same insight as the purchase path, pointing the other way: there, the
atomic claim precedes the work it authorises; here, the atomic transition
precedes the compensation it authorises.

**`inventory.release` is called unconditionally** because it is already a no-op
for plans without a counter. Guarding it with a plan-type test would reintroduce
the pack-ness-versus-inventory confusion that step 05 avoided.

**No refund.** Out of scope, and stated in `SUBMISSION.md` rather than implied.

### 4.2 `PurchaseAlreadyCancelledException` → `409 purchase_already_cancelled`

Same shape as the other six: plain, HTTP-free, carries the id.

### 4.3 The endpoint

```java
@PostMapping("/{id}/cancel")
PurchaseResponse cancel(@PathVariable UUID id) {
    return PurchaseAssembler.toResponse(service.cancel(id));
}
```

`200`, not `204`: the updated purchase is useful and the client should not have
to re-fetch to see `status: "cancelled"`.

## 5. Tests — `PurchaseCancelTest`

| Test | Asserts |
|---|---|
| `cancelSetsStatusAndReturnsPurchase` | status becomes `CANCELLED` |
| `cancelRestoresPromoInventory` | 5 → 4 → cancel → 5 |
| `cancelReleasesUnlimitedClaim` | member can buy unlimited again afterwards |
| `doubleCancelIsRefused` | second call throws `PurchaseAlreadyCancelledException` |
| `cancelUnknownPurchaseThrows` | `PurchaseNotFoundException` |
| **`concurrentCancelsRestoreExactlyOneSlot`** | **500 concurrent cancels of ONE purchase → exactly 1 success, inventory back to exactly 5, never 6** |
| `cancelDoesNotRestoreUnlimitedInventory` | cancelling a no-counter plan leaves `remaining` empty |

`concurrentCancelsRestoreExactlyOneSlot` is the test that would fail under
restore-then-CAS. It is the reason this step is not a five-minute change.

## 6. Verification

```bash
./gradlew test --tests '*PurchaseCancelTest' --console=plain
./gradlew test --console=plain                      # full suite

# the eleventh row exists
grep -c "entry(" src/main/java/com/glofox/memberships/web/ApiExceptionHandler.java   # expect 11

# live
curl -X POST localhost:8080/v1/memberships/purchases/{id}/cancel   # 200, status cancelled
curl -X POST localhost:8080/v1/memberships/purchases/{id}/cancel   # 409 purchase_already_cancelled
```

**Mutation check to run:** swap the CAS and the restores so the restores happen
first, then run `concurrentCancelsRestoreExactlyOneSlot`. Expect inventory above
5. Then revert.
