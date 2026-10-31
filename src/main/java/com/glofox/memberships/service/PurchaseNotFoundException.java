package com.glofox.memberships.service;

import java.util.UUID;

/** No purchase with this id exists. */
public final class PurchaseNotFoundException extends RuntimeException {

    private final UUID purchaseId;

    public PurchaseNotFoundException(UUID purchaseId) {
        super("Purchase not found: " + purchaseId);
        this.purchaseId = purchaseId;
    }

    public UUID purchaseId() {
        return purchaseId;
    }
}
