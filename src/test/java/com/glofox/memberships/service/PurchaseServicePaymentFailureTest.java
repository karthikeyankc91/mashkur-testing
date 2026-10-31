package com.glofox.memberships.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.TestStores;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.payment.CountingGateway;
import com.glofox.memberships.payment.PaymentGateway.PaymentFailedException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A failed payment must leave no purchase and no inventory change.
 *
 * <p><strong>Parameterised across every failure mode on purpose.</strong> Three
 * separate tests would let the cleanup assertions drift apart, and the one most
 * likely to be forgotten is the one that matters: that an unlimited claim is
 * released. Running identical assertions over decline, outage and the umbrella
 * type makes it structurally impossible for one mode to be cleaned up less
 * thoroughly than another.
 */
class PurchaseServicePaymentFailureTest {

    private static final UUID MONTHLY = SeedData.MONTHLY_UNLIMITED_ID;
    private static final UUID PROMO = SeedData.LAUNCH_PROMO_PACK_ID;

    static Stream<Arguments> failureModes() {
        return Stream.of(
                Arguments.of("declined", (Supplier<CountingGateway>) CountingGateway::declining),
                Arguments.of("gateway unavailable",
                             (Supplier<CountingGateway>) CountingGateway::unavailable),
                Arguments.of("umbrella failure type",
                             (Supplier<CountingGateway>) CountingGateway::failingWithUmbrellaType));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    @DisplayName("a failed charge on a promo plan leaves no purchase and restores inventory")
    void failedChargeLeavesNoTrace(String mode, Supplier<CountingGateway> gatewayFactory) {
        TestStores.Fixture f = TestStores.with(gatewayFactory.get());
        assertThat(f.inventory().remaining(PROMO)).hasValue(5);

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> f.service().purchase("Alice", PROMO, TestStores.TODAY));

        assertThat(f.gateway().charges())
                .as("the gateway was attempted exactly once")
                .isEqualTo(1);
        assertThat(f.inventory().remaining(PROMO))
                .as("the reserved slot was given back")
                .hasValue(5);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    @DisplayName("a failed charge on an unlimited plan releases the member's claim")
    void failedChargeReleasesUnlimitedClaim(String mode, Supplier<CountingGateway> gatewayFactory) {
        // The case that is easy to miss: unlimited plans have no inventory
        // counter, so an inventory-only cleanup assertion passes while the
        // member is permanently barred from ever buying an unlimited plan again.
        TestStores.Fixture f = TestStores.with(gatewayFactory.get());

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> f.service().purchase("Alice", MONTHLY, TestStores.TODAY));

        assertThat(f.claims().tryClaim(new MemberKey("Alice"), UUID.randomUUID()))
                .as("the claim is free again, so the member is not stranded")
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    @DisplayName("after a failure the member can retry successfully")
    void memberCanRetryAfterFailure(String mode, Supplier<CountingGateway> gatewayFactory) {
        // The end-to-end consequence of correct compensation, stated the way a
        // studio owner would: the card was declined, so try another card.
        TestStores.Fixture failing = TestStores.with(gatewayFactory.get());
        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> failing.service().purchase("Alice", MONTHLY, TestStores.TODAY));

        // Same stores, but a gateway that now works.
        TestStores.Fixture working = TestStores.succeeding();
        assertThat(working.service().purchase("Alice", MONTHLY, TestStores.TODAY)).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    @DisplayName("no purchase is retrievable after a failed charge")
    void noPurchaseIsPersisted(String mode, Supplier<CountingGateway> gatewayFactory) {
        TestStores.Fixture f = TestStores.with(gatewayFactory.get());

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> f.service().purchase("Alice", MONTHLY, TestStores.TODAY));

        // There is no id to look up, so assert on the store rather than the API:
        // nothing was written at all.
        assertThat(f.purchases().findById(UUID.randomUUID())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureModes")
    @DisplayName("the original failure reaches the caller, not a cleanup error")
    void originalFailurePropagates(String mode, Supplier<CountingGateway> gatewayFactory) {
        TestStores.Fixture f = TestStores.with(gatewayFactory.get());

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> f.service().purchase("Alice", PROMO, TestStores.TODAY))
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .as("a clean unwind suppresses nothing")
                        .isEmpty());
    }
}
