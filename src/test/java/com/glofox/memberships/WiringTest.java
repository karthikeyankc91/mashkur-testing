package com.glofox.memberships;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.payment.AlwaysSucceedingPaymentGateway;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What the real application context actually contains.
 *
 * <p>Costs nothing to run: it declares no bean overrides, so it shares the
 * cached context that {@code contextLoads} already builds. That is also why it
 * is a separate class rather than assertions added to the integration test —
 * and it is the only place that can assert anything about the <em>real</em>
 * payment gateway bean, since the integration context replaces it.
 */
@SpringBootTest
class WiringTest {

    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PlanCatalogue catalogue;
    @Autowired
    private InventoryStore inventory;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private Clock clock;

    @Test
    @DisplayName("the default payment gateway is the vendor bean, unreplaced")
    void defaultGatewayIsTheVendorBean() {
        assertThat(gateway).isInstanceOf(AlwaysSucceedingPaymentGateway.class);
    }

    @Test
    @DisplayName("the catalogue is fully seeded before any traffic is served")
    void catalogueIsSeededBeforeTrafficIsServed() {
        assertThat(catalogue.listInSeedOrder()).hasSize(4);
        assertThat(catalogue.find(SeedData.LEGACY_PLAN_ID))
                .get()
                .extracting(Plan::active)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("only the promo pack has finite inventory")
    void onlyThePromoPackHasFiniteInventory() {
        // Asserts the null-means-unlimited mapping at the BEAN level, which
        // nothing else does: the store tests cover the primitive and the
        // controller test covers the wire format, but this covers the seeding.
        assertThat(inventory.remaining(SeedData.LAUNCH_PROMO_PACK_ID)).hasValue(5);
        assertThat(inventory.remaining(SeedData.MONTHLY_UNLIMITED_ID)).isEmpty();
        assertThat(inventory.remaining(SeedData.TEN_CLASS_PACK_ID)).isEmpty();
        assertThat(inventory.remaining(SeedData.LEGACY_PLAN_ID)).isEmpty();
    }

    @Test
    @DisplayName("the service and the single clock bean are wired")
    void serviceAndClockAreWired() {
        assertThat(purchaseService).isNotNull();
        assertThat(clock).isNotNull();
    }
}
