package com.glofox.memberships.domain;

/**
 * Lifecycle of a purchase.
 *
 * <p>There is deliberately no {@code FAILED} state. A failed payment leaves no
 * purchase at all, so there is nothing for such a state to describe.
 */
public enum PurchaseStatus {
    ACTIVE,
    CANCELLED
}
