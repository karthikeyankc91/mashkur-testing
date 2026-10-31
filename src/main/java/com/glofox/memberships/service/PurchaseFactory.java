package com.glofox.memberships.service;

import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.domain.PlanSnapshot;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds a {@link Purchase}, stamping it from the injected clock.
 *
 * <p>Like {@link StartDatePolicy} this holds a {@link Clock} and exposes no way
 * to read it. The useful side effect is that {@code createdAt} becomes
 * deterministic under a fixed clock, so a test can assert equality on a whole
 * {@code Purchase} record — impossible if the service called
 * {@code Instant.now()} inline.
 */
public record PurchaseFactory(Clock clock) {

    public Purchase create(UUID id,
                           String memberName,
                           MemberKey key,
                           Plan plan,
                           LocalDate startDate,
                           String transactionId) {
        return new Purchase(id,
                            memberName,
                            key,
                            PlanSnapshot.of(plan),
                            // The amount recorded is the price that was charged,
                            // taken from the same Plan instance, so the two
                            // cannot drift apart.
                            plan.priceCents(),
                            PurchaseStatus.ACTIVE,
                            startDate,
                            transactionId,
                            clock.instant());
    }
}
