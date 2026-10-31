package com.glofox.memberships.domain;

import java.util.UUID;

/**
 * A plan on sale, as the catalogue holds it.
 *
 * <p><strong>There is no {@code remainingSlots} field.</strong> Inventory is
 * mutable and plans are not, so inventory lives in its own structure keyed by
 * plan id. That separation is what lets the catalogue be an immutable map read
 * without locks.
 *
 * @param credits number of classes for a pack, or {@code null} for an unlimited
 *                plan, where the concept does not apply. This is genuine
 *                absence of meaning, which is why it is a nullable
 *                {@code Integer} rather than a sentinel.
 */
public record Plan(UUID id,
                   String name,
                   PlanType type,
                   boolean active,
                   int priceCents,
                   Integer credits) {
}
