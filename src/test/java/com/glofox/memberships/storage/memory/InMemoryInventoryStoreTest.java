package com.glofox.memberships.storage.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boundaries of the inventory primitive, single-threaded first.
 *
 * <p>The concurrency case at the end is deliberately narrow: it proves the
 * <em>primitive</em>. The service-level oversell test in step 07 proves the
 * <em>orchestration</em>, including that exactly five charges occurred. Keeping
 * them separate means a failure localises — if both fail it is this store, if
 * only the step 07 test fails it is the service.
 */
class InMemoryInventoryStoreTest {

    private static final UUID FINITE = UUID.randomUUID();
    private static final UUID UNKNOWN = UUID.randomUUID();

    private InMemoryInventoryStore storeWith(int slots) {
        return new InMemoryInventoryStore(Map.of(FINITE, slots));
    }

    @Test
    @DisplayName("a plan with no entry has unlimited inventory, not zero")
    void absentPlanIsUnlimited() {
        InMemoryInventoryStore store = storeWith(5);

        for (int i = 0; i < 1_000; i++) {
            assertThat(store.tryReserve(UNKNOWN))
                    .as("reservation %d against an unlimited plan", i)
                    .isTrue();
        }
        assertThat(store.remaining(UNKNOWN)).isEmpty();
    }

    @Test
    @DisplayName("a finite plan reserves exactly its seeded count")
    void finiteReservesExactlyN() {
        InMemoryInventoryStore store = storeWith(5);

        for (int i = 0; i < 5; i++) {
            assertThat(store.tryReserve(FINITE)).as("reservation %d", i).isTrue();
        }
        assertThat(store.tryReserve(FINITE)).as("the sixth reservation").isFalse();
        assertThat(store.remaining(FINITE)).hasValue(0);
    }

    @Test
    @DisplayName("an exhausted counter never goes negative however hard it is pushed")
    void neverNegative() {
        InMemoryInventoryStore store = storeWith(5);
        IntStream.range(0, 5).forEach(i -> store.tryReserve(FINITE));

        for (int i = 0; i < 100; i++) {
            assertThat(store.tryReserve(FINITE)).isFalse();
        }
        assertThat(store.remaining(FINITE)).hasValue(0);
    }

    @Test
    @DisplayName("release returns a slot and makes it reservable again")
    void releaseRestores() {
        InMemoryInventoryStore store = storeWith(5);
        IntStream.range(0, 5).forEach(i -> store.tryReserve(FINITE));

        store.release(FINITE);

        assertThat(store.remaining(FINITE)).hasValue(1);
        assertThat(store.tryReserve(FINITE)).isTrue();
        assertThat(store.remaining(FINITE)).hasValue(0);
    }

    @Test
    @DisplayName("releasing an unlimited plan is a silent no-op, not an error")
    void releaseOnUnlimitedIsNoop() {
        InMemoryInventoryStore store = storeWith(5);

        assertThatCode(() -> store.release(UNKNOWN)).doesNotThrowAnyException();
        assertThat(store.remaining(UNKNOWN)).isEmpty();
    }

    @Test
    @DisplayName("a plan seeded at zero is sold out from boot - zero is not null")
    void zeroSeededIsSoldOutFromBoot() {
        InMemoryInventoryStore store = storeWith(0);

        assertThat(store.remaining(FINITE))
                .as("an entry exists; it is not treated as unlimited")
                .hasValue(0);
        assertThat(store.tryReserve(FINITE)).isFalse();
    }

    @Test
    @DisplayName("500 concurrent reservations against 5 slots yield exactly 5 winners")
    void primitiveNeverOversellsUnderContention() throws Exception {
        InMemoryInventoryStore store = storeWith(5);
        int contenders = 500;

        List<Callable<Boolean>> attempts = IntStream.range(0, contenders)
                .mapToObj(i -> (Callable<Boolean>) () -> store.tryReserve(FINITE))
                .toList();

        long winners;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = pool.invokeAll(attempts);
            winners = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
        }

        assertThat(winners).as("exactly the seeded slot count may win").isEqualTo(5);
        assertThat(store.remaining(FINITE)).as("and the counter settles at zero").hasValue(0);
    }
}
