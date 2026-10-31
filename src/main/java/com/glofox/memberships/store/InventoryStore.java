package com.glofox.memberships.store;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Finite plan inventory, and the atomic claim on it.
 *
 * <p>Entries exist <strong>only</strong> for plans with finite inventory.
 * Absence of an entry means unlimited inventory, not zero — which is why
 * {@link #remaining} returns an {@link OptionalInt}.
 */
public interface InventoryStore {

    /**
     * Atomically claims one unit.
     *
     * @return {@code true} if a unit was claimed, or if this plan has unlimited
     *         inventory; {@code false} only when a finite plan is exhausted.
     *         Callers must not read-then-write around this method — the whole
     *         point is that the check and the decrement are one operation.
     */
    boolean tryReserve(UUID planId);

    /** Returns one claimed unit. A no-op for plans with unlimited inventory. */
    void release(UUID planId);

    /**
     * Remaining units, or {@link OptionalInt#empty()} for unlimited inventory.
     *
     * <p><strong>No {@code int}-returning overload may ever be added.</strong> A
     * convenience that returned {@code 0} for unlimited inventory would make
     * {@code remaining(id) > 0} look like a correct sold-out check while
     * silently refusing to sell every plan that has no inventory limit. The
     * awkward return type is the guard rail.
     */
    OptionalInt remaining(UUID planId);
}
