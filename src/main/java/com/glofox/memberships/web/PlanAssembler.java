package com.glofox.memberships.web;

import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.web.dto.PlanResponse;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Domain plan to wire representation.
 *
 * <p>Static rather than a {@code @Component}: it has no state and no
 * dependencies, so making it a bean would add wiring ceremony and force an
 * {@code @Import} into every slice test. Pure functions do not need a container.
 */
public final class PlanAssembler {

    private PlanAssembler() {
    }

    public static PlanResponse toResponse(Plan plan, OptionalInt remaining) {
        // Named local rather than a ternary inside the constructor call. The
        // int/null ternary compiles but is the shape that produces surprise
        // unboxing NPEs when later edited; this makes the boxing deliberate.
        Integer remainingSlots = remaining.isEmpty() ? null : remaining.getAsInt();

        return new PlanResponse(
                plan.id(),
                plan.name(),
                // The single place the enum becomes wire vocabulary.
                plan.type().name().toLowerCase(Locale.ROOT),
                plan.active(),
                plan.priceCents(),
                plan.credits(),
                remainingSlots);
    }
}
