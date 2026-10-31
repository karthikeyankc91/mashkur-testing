package com.glofox.memberships.web.dto;

import java.util.UUID;

/** The plan as it was when purchased. Nested, so its historical nature shows. */
public record PlanSnapshotResponse(UUID planId,
                                   String name,
                                   String type,
                                   int priceCents,
                                   Integer credits) {
}
