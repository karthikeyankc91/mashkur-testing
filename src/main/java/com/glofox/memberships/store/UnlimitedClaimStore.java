package com.glofox.memberships.store;

import com.glofox.memberships.domain.MemberKey;
import java.util.UUID;

/**
 * Holds the one active unlimited membership a member is allowed.
 *
 * <p>This is the same claim-or-fail shape as {@link InventoryStore}: a single
 * atomic operation decides, and the caller compensates by releasing if a later
 * step fails. The business rule it enforces has exactly the check-then-act race
 * that finite inventory has, even though the brief only mentions the latter.
 */
public interface UnlimitedClaimStore {

    /**
     * Atomically claims the unlimited slot for this member.
     *
     * @return {@code true} if the slot was claimed, {@code false} if the member
     *         already holds one.
     */
    boolean tryClaim(MemberKey key, UUID purchaseId);

    /**
     * Releases the slot, but <strong>only if {@code purchaseId} still owns
     * it</strong>.
     *
     * <p>The purchase id is not decoration. Without it the release maps to an
     * unconditional {@code remove(key)}, and this sequence corrupts state: a
     * member buys unlimited (claim held by P1), cancels P1, buys again (claim
     * now held by P2), then a duplicated cancel of P1 arrives and releases
     * <em>P2's</em> claim. With the id it maps to
     * {@code ConcurrentHashMap.remove(key, value)} and the stale release is a
     * no-op.
     *
     * @return {@code true} if this call released the claim.
     */
    boolean release(MemberKey key, UUID purchaseId);
}
