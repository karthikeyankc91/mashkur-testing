package com.glofox.memberships.service;

import com.glofox.memberships.domain.Plan;
import java.util.UUID;

/** The plan exists but is not on sale. */
public final class PlanInactiveException extends RuntimeException {

    private final UUID planId;
    private final String planName;

    public PlanInactiveException(Plan plan) {
        super("Plan is not active: " + plan.name());
        this.planId = plan.id();
        this.planName = plan.name();
    }

    public UUID planId() {
        return planId;
    }

    public String planName() {
        return planName;
    }
}
