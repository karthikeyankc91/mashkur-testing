package com.glofox.memberships.storage.memory;

import com.glofox.memberships.store.IdempotencyStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Idempotency keys held in memory.
 *
 * <p><strong>Known limitation:</strong> keys are never evicted, so this grows
 * without bound on a caller-controlled key. A real implementation needs a TTL.
 * Recorded in the submission notes rather than hidden.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /** A null {@code purchaseId} means the request is still in flight. */
    private record Entry(String fingerprint, UUID purchaseId) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Claim claim(String key, String fingerprint) {
        Entry existing = entries.putIfAbsent(key, new Entry(fingerprint, null));

        if (existing == null) {
            return new Claim.Acquired();
        }
        // Fingerprint before in-flight, deliberately: a key reused with a
        // different body is a client bug worth reporting even while the first
        // request is still running.
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Claim.Conflict();
        }
        return existing.purchaseId() == null
                ? new Claim.InProgress()
                : new Claim.Replay(existing.purchaseId());
    }

    @Override
    public void complete(String key, UUID purchaseId) {
        Entry inFlight = entries.get(key);
        if (inFlight != null) {
            // Value-safe replace, as everywhere else: only the holder of this
            // exact in-flight entry may complete it.
            entries.replace(key, inFlight, new Entry(inFlight.fingerprint(), purchaseId));
        }
    }

    @Override
    public void release(String key) {
        Entry inFlight = entries.get(key);
        // Only remove an entry that is still in flight. A completed entry must
        // survive so that retries keep replaying it.
        if (inFlight != null && inFlight.purchaseId() == null) {
            entries.remove(key, inFlight);
        }
    }
}
