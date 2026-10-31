package com.glofox.memberships.storage.memory;

import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.store.UnlimitedClaimStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The one-active-unlimited-membership rule, enforced by a map rather than by
 * any comparison written here.
 *
 * <p>A {@code ConcurrentHashMap} is genuinely required, unlike
 * {@link InMemoryInventoryStore}: these keys are added and removed at runtime.
 * The contrast between the two stores is the interesting part — same
 * claim-or-fail shape, different structure, because one has a fixed key set and
 * the other does not.
 *
 * <p>{@link MemberKey} normalised its value in its own constructor, so the
 * record's generated {@code equals}/{@code hashCode} operate on the folded
 * form. {@code putIfAbsent} therefore collides {@code "Alice"} with
 * {@code "alice"} and {@code " ALICE "} with no matching logic in this class.
 * The normalisation and this one call are a single mechanism split across two
 * files.
 */
public final class InMemoryUnlimitedClaimStore implements UnlimitedClaimStore {

    private final ConcurrentMap<MemberKey, UUID> claims = new ConcurrentHashMap<>();

    /** {@code putIfAbsent} returns {@code null} exactly once per key. */
    @Override
    public boolean tryClaim(MemberKey key, UUID purchaseId) {
        return claims.putIfAbsent(key, purchaseId) == null;
    }

    /**
     * Two-argument {@code remove}, so a release only lands if this purchase
     * still owns the claim. Returns {@code false} rather than throwing, letting
     * the caller log an invariant breach without the stale release corrupting a
     * newer claim.
     */
    @Override
    public boolean release(MemberKey key, UUID purchaseId) {
        return claims.remove(key, purchaseId);
    }
}
