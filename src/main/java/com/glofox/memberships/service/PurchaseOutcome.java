package com.glofox.memberships.service;

import com.glofox.memberships.domain.Purchase;

/**
 * A purchase, and whether it was created now or replayed from an earlier
 * identical request.
 *
 * @param replayed true when an idempotency key matched a completed request, so
 *                 nothing was charged on this call
 */
public record PurchaseOutcome(Purchase purchase, boolean replayed) {

    public static PurchaseOutcome created(Purchase purchase) {
        return new PurchaseOutcome(purchase, false);
    }

    public static PurchaseOutcome replayed(Purchase purchase) {
        return new PurchaseOutcome(purchase, true);
    }
}
