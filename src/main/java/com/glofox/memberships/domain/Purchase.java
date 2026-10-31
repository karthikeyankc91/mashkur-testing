package com.glofox.memberships.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A completed purchase.
 *
 * <p>Both {@code memberName} and {@code memberKey} are stored: the first is
 * what the member actually typed, kept for display, and the second is the
 * normalised key used for matching. The key never appears on the wire.
 *
 * <p>{@code startDate} is a {@link LocalDate} and {@code createdAt} is an
 * {@link Instant}. These are two different kinds of time and so deliberately
 * two different types: the start date is a calendar date chosen by a human,
 * the creation stamp is a machine instant.
 */
public record Purchase(UUID id,
                       String memberName,
                       MemberKey memberKey,
                       PlanSnapshot plan,
                       int amountCents,
                       PurchaseStatus status,
                       LocalDate startDate,
                       String transactionId,
                       Instant createdAt) {

    /**
     * Returns a copy with a new status. Records are immutable, so a status
     * transition replaces the purchase rather than mutating it.
     */
    public Purchase withStatus(PurchaseStatus next) {
        return new Purchase(id, memberName, memberKey, plan, amountCents,
                            next, startDate, transactionId, createdAt);
    }
}
