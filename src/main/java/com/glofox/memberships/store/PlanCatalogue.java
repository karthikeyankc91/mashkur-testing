package com.glofox.memberships.store;

import com.glofox.memberships.domain.Plan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only access to the seeded plans. Nothing mutates a plan. */
public interface PlanCatalogue {

    Optional<Plan> find(UUID planId);

    /**
     * All plans, active and inactive, in the order they were seeded.
     *
     * <p>Order is part of the contract: the listing endpoint promises a stable
     * order, and seed order is the only order the studio has expressed.
     */
    List<Plan> listInSeedOrder();
}
