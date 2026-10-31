package com.glofox.memberships.web;

import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.web.dto.PlanSnapshotResponse;
import com.glofox.memberships.web.dto.PurchaseResponse;
import java.util.Locale;

/** Domain purchase to wire representation. Static, matching PlanAssembler. */
public final class PurchaseAssembler {

    private PurchaseAssembler() {
    }

    public static PurchaseResponse toResponse(Purchase purchase) {
        return new PurchaseResponse(
                purchase.id(),
                purchase.memberName(),
                new PlanSnapshotResponse(
                        purchase.plan().planId(),
                        purchase.plan().name(),
                        purchase.plan().type().name().toLowerCase(Locale.ROOT),
                        purchase.plan().priceCents(),
                        purchase.plan().credits()),
                purchase.amountCents(),
                purchase.status().name().toLowerCase(Locale.ROOT),
                purchase.startDate(),
                purchase.transactionId(),
                purchase.createdAt());
    }
}
