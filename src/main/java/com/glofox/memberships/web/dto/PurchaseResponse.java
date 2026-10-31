package com.glofox.memberships.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A purchase as the API presents it.
 *
 * <p>{@code memberKey} is deliberately absent: it is an internal matching
 * concern and the member never typed it.
 *
 * <p>{@code amountCents} sits outside the plan snapshot even though it equals
 * {@code plan.priceCents} today. They are different facts — what the plan cost
 * and what this member was charged — and the amount charged is requested as a
 * first-class field.
 */
public record PurchaseResponse(UUID id,
                               String memberName,
                               PlanSnapshotResponse plan,
                               int amountCents,
                               String status,
                               LocalDate startDate,
                               String transactionId,
                               Instant createdAt) {
}
