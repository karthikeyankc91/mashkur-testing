package com.glofox.memberships.store;

import java.util.UUID;

/**
 * Deduplicates retried purchase requests by caller-supplied key.
 *
 * <p>This is the same claim-or-fail shape as {@link InventoryStore} and
 * {@link UnlimitedClaimStore}: claim atomically, do the work, release if
 * anything fails. The third instance of one pattern rather than a third
 * pattern.
 */
public interface IdempotencyStore {

    /**
     * What a claim attempt found.
     *
     * <p>Sealed, and not a boolean, because there are four distinct answers and
     * conflating any two of them is a bug. Exhaustive {@code switch} means a
     * fifth state could not be added and silently ignored.
     */
    sealed interface Claim {

        /** The key is ours; proceed with the purchase. */
        record Acquired() implements Claim {
        }

        /** Another request holds this key and has not finished. */
        record InProgress() implements Claim {
        }

        /** This key already produced a purchase; return that one. */
        record Replay(UUID purchaseId) implements Claim {
        }

        /** This key was used for a different payload. */
        record Conflict() implements Claim {
        }
    }

    /**
     * Attempts to claim {@code key} for a request with this {@code fingerprint}.
     *
     * @param fingerprint a digest of the request payload, so reuse of one key
     *                    with different content is detectable
     */
    Claim claim(String key, String fingerprint);

    /** Records the purchase this key produced, so later retries replay it. */
    void complete(String key, UUID purchaseId);

    /**
     * Gives the key back after a failure.
     *
     * <p>Without this, a client retrying after a declined card would be told its
     * request is already in progress forever.
     */
    void release(String key);
}
