package com.glofox.memberships.domain;

/**
 * The two kinds of plan this studio sells.
 *
 * <p>No {@code @JsonValue} and no wire-format concern: the lowercase form
 * ({@code "unlimited"}) is produced by the web assembler. The domain does not
 * know it is ever serialised.
 */
public enum PlanType {
    UNLIMITED,
    PACK
}
