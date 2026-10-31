package com.glofox.memberships.service;

import java.util.UUID;

/** No plan with this id exists. */
public final class PlanNotFoundException extends RuntimeException {

    private final UUID planId;

    public PlanNotFoundException(UUID planId) {
        super("Plan not found: " + planId);
        this.planId = planId;
    }

    public UUID planId() {
        return planId;
    }
}
