package com.glofox.memberships.service;

import com.glofox.memberships.domain.Plan;
import java.util.UUID;

/** The plan has finite inventory and none remains. */
public final class PlanSoldOutException extends RuntimeException {

    private final UUID planId;
    private final String planName;

    public PlanSoldOutException(Plan plan) {
        super("Plan is sold out: " + plan.name());
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
