package com.glofox.memberships.domain;

import java.util.UUID;

/**
 * What a plan looked like at the moment it was sold.
 *
 * <p>A purchase stores this rather than a bare plan id so that a later price
 * change cannot rewrite what a member was charged. The {@link #of(Plan)}
 * factory is the only way one is built, so a snapshot can never disagree with
 * the plan it was taken from.
 */
public record PlanSnapshot(UUID planId,
                           String name,
                           PlanType type,
                           int priceCents,
                           Integer credits) {

    public static PlanSnapshot of(Plan plan) {
        return new PlanSnapshot(plan.id(), plan.name(), plan.type(),
                                plan.priceCents(), plan.credits());
    }
}
