package com.glofox.memberships.config;

import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.service.PurchaseFactory;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.service.StartDatePolicy;
import com.glofox.memberships.store.IdempotencyStore;
import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import com.glofox.memberships.store.PurchaseStore;
import com.glofox.memberships.store.UnlimitedClaimStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the service layer, which carries no Spring annotations of its own.
 *
 * <p>Public so slice tests can {@code @Import} it, for the same compile-time
 * reason as {@link SeedingConfig}.
 */
@Configuration
public class ServiceConfig {

    @Bean
    StartDatePolicy startDatePolicy(Clock clock) {
        return new StartDatePolicy(clock);
    }

    @Bean
    PurchaseFactory purchaseFactory(Clock clock) {
        return new PurchaseFactory(clock);
    }

    /** {@code PaymentGateway} resolves to the vendor's component, as shipped. */
    @Bean
    PurchaseService purchaseService(PlanCatalogue catalogue,
                                    InventoryStore inventory,
                                    UnlimitedClaimStore claims,
                                    PurchaseStore purchases,
                                    IdempotencyStore idempotency,
                                    PaymentGateway gateway,
                                    StartDatePolicy startDates,
                                    PurchaseFactory factory) {
        return new PurchaseService(catalogue, inventory, claims, purchases, idempotency,
                                   gateway, startDates, factory);
    }
}
