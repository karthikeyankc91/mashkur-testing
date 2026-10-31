package com.glofox.memberships.storage.memory;

import com.glofox.memberships.store.InventoryStore;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Lock-free finite inventory. This class is where "never oversold, never
 * negative" is actually enforced.
 *
 * <p>The map is <strong>immutable with mutable values</strong>: the key set is
 * fixed at construction because plans are seeded once, and only the
 * {@link AtomicInteger} values change. A {@code ConcurrentHashMap} here would
 * be guarding a structure that cannot be structurally modified.
 */
public final class InMemoryInventoryStore implements InventoryStore {

    private final Map<UUID, AtomicInteger> slots;

    /**
     * @param finiteInventoryOnly plans that have an inventory limit. Plans with
     *                            unlimited inventory must be absent, not present
     *                            with a large number: absence is how unlimited
     *                            is represented.
     */
    public InMemoryInventoryStore(Map<UUID, Integer> finiteInventoryOnly) {
        this.slots = finiteInventoryOnly.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> new AtomicInteger(e.getValue())));
    }

    /**
     * Atomically claims one slot.
     *
     * <p>Three details, each load-bearing:
     *
     * <ol>
     *   <li><strong>{@code null} short-circuits before any arithmetic.</strong>
     *       Absence <em>is</em> unlimited inventory, so there is no comparison
     *       that could be written backwards.
     *   <li><strong>The guard lives inside the lambda.</strong>
     *       {@code n > 0 ? n - 1 : n} leaves no window between testing and
     *       decrementing, so the counter is structurally incapable of going
     *       negative — no retry loop, no lock.
     *   <li><strong>{@code getAndUpdate}, not {@code updateAndGet}.</strong> The
     *       <em>previous</em> value is what says whether you won.
     *       {@code updateAndGet} returning {@code 0} cannot distinguish "I took
     *       the last slot" from "there were none left".
     * </ol>
     */
    @Override
    public boolean tryReserve(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        if (counter == null) {
            return true;
        }
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0;
    }

    /**
     * Returns one slot. A no-op for unlimited plans.
     *
     * <p><strong>Deliberately does not clamp at the seeded maximum.</strong>
     * Clamping would mask a double-release, which is precisely the bug the
     * cancel-path tests exist to catch. Exactly-once release is the caller's
     * obligation, and this counter's final value is the assertion that proves
     * the caller met it.
     */
    @Override
    public void release(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    @Override
    public OptionalInt remaining(UUID planId) {
        AtomicInteger counter = slots.get(planId);
        return counter == null ? OptionalInt.empty() : OptionalInt.of(counter.get());
    }
}
