package com.glofox.memberships.web.dto;

import java.util.UUID;

/**
 * A plan as the API presents it.
 *
 * <p><strong>Wire names are snake_case</strong> — {@code priceCents} is
 * serialised as {@code price_cents} — via the global
 * {@code spring.jackson.property-naming-strategy} setting rather than
 * per-field annotations. That keeps Jackson out of this file entirely, at the
 * cost of the wire contract not being visible here; the controller test asserts
 * the snake_case names so the cost is covered.
 *
 * @param type        the lowercase wire vocabulary ({@code "unlimited"}),
 *                    produced by the assembler so no serialisation concern
 *                    reaches the domain enum
 * @param credits     boxed so it can be {@code null} for unlimited plans
 * @param remainingSlots boxed so it can be {@code null}, meaning unlimited
 *                    inventory. {@code null} and {@code 0} are different
 *                    answers and must stay distinguishable
 */
public record PlanResponse(UUID id,
                           String name,
                           String type,
                           boolean active,
                           int priceCents,
                           Integer credits,
                           Integer remainingSlots) {
}
