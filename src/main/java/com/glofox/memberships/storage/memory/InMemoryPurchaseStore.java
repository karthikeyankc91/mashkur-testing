package com.glofox.memberships.storage.memory;

import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import com.glofox.memberships.store.PurchaseStore;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Purchase storage. Keys appear at runtime, so a concurrent map is required. */
public final class InMemoryPurchaseStore implements PurchaseStore {

    private final ConcurrentMap<UUID, Purchase> purchases = new ConcurrentHashMap<>();

    @Override
    public void save(Purchase purchase) {
        purchases.put(purchase.id(), purchase);
    }

    @Override
    public Optional<Purchase> findById(UUID id) {
        return Optional.ofNullable(purchases.get(id));
    }

    /**
     * Compare-and-set on status.
     *
     * <p>{@code replace(key, oldValue, newValue)} compares by {@code equals},
     * and {@link Purchase} is a record with structural equality. So if two
     * threads both read the same {@code current} and both compute
     * {@code withStatus}, the first {@code replace} succeeds and the second
     * returns {@code false} because the stored value no longer equals what it
     * read.
     *
     * <p>The {@code status() != expected} test is a <em>fast path only</em> —
     * {@code replace} is the decision. Returning on the test alone would be the
     * read-then-write race this method exists to remove.
     */
    @Override
    public boolean compareAndSetStatus(UUID id, PurchaseStatus expected, PurchaseStatus next) {
        Purchase current = purchases.get(id);
        if (current == null || current.status() != expected) {
            return false;
        }
        return purchases.replace(id, current, current.withStatus(next));
    }
}
