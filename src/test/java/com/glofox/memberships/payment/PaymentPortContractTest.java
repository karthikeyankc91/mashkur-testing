package com.glofox.memberships.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.payment.PaymentGateway.PaymentDeclinedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentFailedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentGatewayUnavailableException;
import com.glofox.memberships.payment.PaymentGateway.PaymentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the payment port's contract, including the property that makes the
 * extension safe: both new types are still catchable as the umbrella type.
 */
class PaymentPortContractTest {

    @Test
    @DisplayName("the vendor default gateway still succeeds and returns a transaction id")
    void defaultGatewaySucceeds() {
        PaymentResult result = new AlwaysSucceedingPaymentGateway().charge(8_000, "Alice");

        assertThat(result.transactionId()).isNotBlank();
    }

    @Test
    @DisplayName("a decline is caught by the umbrella type")
    void declineIsCaughtByUmbrellaType() {
        // This is the property that makes extending a vendor interface safe:
        // existing code that catches PaymentFailedException keeps working.
        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> CountingGateway.declining().charge(100, "Alice"));

        assertThat(new PaymentDeclinedException("x"))
                .isInstanceOf(PaymentFailedException.class);
    }

    @Test
    @DisplayName("an outage is caught by the umbrella type")
    void outageIsCaughtByUmbrellaType() {
        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> CountingGateway.unavailable().charge(100, "Alice"));

        assertThat(new PaymentGatewayUnavailableException("x"))
                .isInstanceOf(PaymentFailedException.class);
    }

    @Test
    @DisplayName("the two failure modes remain distinguishable from each other")
    void failureModesAreDistinct() {
        assertThat(new PaymentDeclinedException("x"))
                .isNotInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(new PaymentGatewayUnavailableException("x"))
                .isNotInstanceOf(PaymentDeclinedException.class);
    }

    @Test
    @DisplayName("failures are counted, so 'called once despite failing' is assertable")
    void failedChargesAreStillCounted() {
        CountingGateway declining = CountingGateway.declining();

        assertThatExceptionOfType(PaymentDeclinedException.class)
                .isThrownBy(() -> declining.charge(2_500, "Alice"));

        assertThat(declining.charges()).isEqualTo(1);
        assertThat(declining.amounts()).containsExactly(2_500);
    }

    @Test
    @DisplayName("the counting gateway records amounts in cents, in order")
    void recordsAmounts() {
        CountingGateway gateway = CountingGateway.succeeding();

        gateway.charge(8_000, "Alice");
        gateway.charge(2_500, "Bob");

        assertThat(gateway.charges()).isEqualTo(2);
        assertThat(gateway.amounts()).containsExactly(8_000, 2_500);
    }
}
