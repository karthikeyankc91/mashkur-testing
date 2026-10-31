package com.glofox.memberships.config;

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
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the four stores, already seeded.
 *
 * <p>Seeding happens in the constructor rather than in a startup listener, so
 * <strong>no empty state ever exists</strong> — the bean does not exist until it
 * is populated. That is a stronger guarantee than seeding after construction,
 * where a request arriving during startup could observe an empty catalogue.
 *
 * <p>The adapters themselves carry no Spring annotations. They are plain classes
 * this configuration constructs, which is why they unit-test with no
 * application context.
 *
 * <p>Public only so that slice tests can {@code @Import} it and exercise the
 * real seeded stores instead of mocks. {@code @Import} resolves a class literal
 * at compile time, so a package-private configuration is unreachable from a
 * test in another package.
 */
@Configuration
public class SeedingConfig {

    @Bean
    PlanCatalogue planCatalogue() {
        return new InMemoryPlanCatalogue(
                SeedData.PLANS.stream().map(PlanSeedMapper::toPlan).toList());
    }

    /**
     * Inventory for finite plans only.
     *
     * <p><strong>The filter is the entire implementation of "null means
     * unlimited".</strong> Only the Launch Promo Pack contributes an entry; the
     * other three plans have no counter at all, so there is no number for a
     * sold-out check to compare against incorrectly.
     */
    @Bean
    InventoryStore inventoryStore() {
        return new InMemoryInventoryStore(
                SeedData.PLANS.stream()
                        .filter(seed -> seed.remainingSlots() != null)
                        .collect(Collectors.toMap(SeedData.PlanSeed::id,
                                                  SeedData.PlanSeed::remainingSlots)));
    }

    @Bean
    UnlimitedClaimStore unlimitedClaimStore() {
        return new InMemoryUnlimitedClaimStore();
    }

    @Bean
    PurchaseStore purchaseStore() {
        return new InMemoryPurchaseStore();
    }

    @Bean
    IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
