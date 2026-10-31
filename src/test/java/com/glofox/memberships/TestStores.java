package com.glofox.memberships;

import com.glofox.memberships.config.PlanSeedMapper;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.payment.CountingGateway;
import com.glofox.memberships.service.PurchaseFactory;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.service.StartDatePolicy;
import com.glofox.memberships.storage.memory.InMemoryIdempotencyStore;
import com.glofox.memberships.storage.memory.InMemoryInventoryStore;
import com.glofox.memberships.storage.memory.InMemoryPlanCatalogue;
import com.glofox.memberships.storage.memory.InMemoryPurchaseStore;
import com.glofox.memberships.storage.memory.InMemoryUnlimitedClaimStore;
import com.glofox.memberships.store.IdempotencyStore;
import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import com.glofox.memberships.store.PurchaseStore;
import com.glofox.memberships.store.UnlimitedClaimStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

/**
 * Freshly seeded stores and a ready-wired service, for tests that want the real
 * thing rather than mocks.
 *
 * <p>Every call constructs new stores, so no test can inherit another's
 * inventory. That matters more than it sounds: a shared promo counter would
 * make test outcomes depend on execution order.
 */
public final class TestStores {

    /** A fixed clock, so "today" is stable and assertions on createdAt work. */
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    public static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private TestStores() {
    }

    public static PlanCatalogue catalogue() {
        return new InMemoryPlanCatalogue(
                SeedData.PLANS.stream().map(PlanSeedMapper::toPlan).toList());
    }

    public static InventoryStore inventory() {
        return new InMemoryInventoryStore(
                SeedData.PLANS.stream()
                        .filter(seed -> seed.remainingSlots() != null)
                        .collect(Collectors.toMap(SeedData.PlanSeed::id,
                                                  SeedData.PlanSeed::remainingSlots)));
    }

    /** Everything a test needs, bundled so assertions can reach the stores. */
    public record Fixture(PlanCatalogue catalogue,
                          InventoryStore inventory,
                          UnlimitedClaimStore claims,
                          PurchaseStore purchases,
                          IdempotencyStore idempotency,
                          CountingGateway gateway,
                          PurchaseService service) {
    }

    public static Fixture with(CountingGateway gateway) {
        return with(gateway, FIXED_CLOCK);
    }

    public static Fixture with(CountingGateway gateway, Clock clock) {
        PlanCatalogue catalogue = catalogue();
        InventoryStore inventory = inventory();
        UnlimitedClaimStore claims = new InMemoryUnlimitedClaimStore();
        PurchaseStore purchases = new InMemoryPurchaseStore();
        IdempotencyStore idempotency = new InMemoryIdempotencyStore();

        PurchaseService service = new PurchaseService(
                catalogue, inventory, claims, purchases, idempotency, gateway,
                new StartDatePolicy(clock), new PurchaseFactory(clock));

        return new Fixture(catalogue, inventory, claims, purchases, idempotency,
                           gateway, service);
    }

    public static Fixture succeeding() {
        return with(CountingGateway.succeeding());
    }

    public static Instant fixedInstant() {
        return FIXED_CLOCK.instant();
    }
}
