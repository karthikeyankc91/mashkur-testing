package com.glofox.memberships.store;

import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import java.util.Optional;
import java.util.UUID;

/** Persistence for purchases. */
public interface PurchaseStore {

    void save(Purchase purchase);

    Optional<Purchase> findById(UUID id);

    /**
     * Atomically moves a purchase from one status to another.
     *
     * <p>Exists so that a status transition and the compensation it authorises
     * (restoring inventory, releasing an unlimited claim) are one decision
     * rather than a read followed by a write. Whoever wins this call is the one
     * caller that compensates; a concurrent second attempt gets {@code false}
     * and must not compensate, or inventory would be restored twice.
     *
     * @return {@code true} if this call performed the transition.
     */
    boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next);
}
