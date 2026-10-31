package com.glofox.memberships.config;

import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.domain.PlanType;

/**
 * Translates vendor {@link SeedData.PlanSeed} records into domain
 * {@link Plan}s. This is the one place a raw {@code String} plan type becomes a
 * typed enum.
 *
 * <p>Extracted rather than kept private inside {@code SeedingConfig} so the
 * fail-fast behaviour below is unit-testable. {@code SeedData} is immutable
 * vendor code that must not be edited, so an unrecognised type cannot be
 * exercised through it — the mapper has to be reachable directly.
 */
public final class PlanSeedMapper {

    private PlanSeedMapper() {
    }

    public static Plan toPlan(SeedData.PlanSeed seed) {
        return new Plan(seed.id(),
                        seed.name(),
                        parseType(seed.type()),
                        seed.active(),
                        seed.priceCents(),
                        seed.credits());
    }

    /**
     * Maps the wire string to the enum, refusing anything unrecognised.
     *
     * <p>Throwing here is the whole fail-fast story: this runs inside a
     * {@code @Bean} method, so an unknown type fails context refresh and the
     * application exits non-zero rather than booting with a plan it cannot
     * reason about. A {@code default -> PlanType.PACK} fallback would turn a
     * deployment error into silent mispricing.
     */
    public static PlanType parseType(String raw) {
        return switch (raw) {
            case "unlimited" -> PlanType.UNLIMITED;
            case "pack" -> PlanType.PACK;
            default -> throw new IllegalStateException("Unknown plan type: " + raw);
        };
    }
}
