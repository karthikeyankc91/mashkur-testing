package com.glofox.memberships.service;

import java.util.UUID;

/** The purchase exists but is not active, so it cannot be cancelled again. */
public final class PurchaseAlreadyCancelledException extends RuntimeException {

    private final UUID purchaseId;

    public PurchaseAlreadyCancelledException(UUID purchaseId) {
        super("Purchase is already cancelled: " + purchaseId);
        this.purchaseId = purchaseId;
    }

    public UUID purchaseId() {
        return purchaseId;
    }
}
