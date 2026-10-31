package com.glofox.memberships.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.Concurrency;
import com.glofox.memberships.Concurrency.Outcome;
import com.glofox.memberships.TestStores;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.payment.CountingGateway;
import com.glofox.memberships.payment.PaymentGateway.PaymentFailedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retry safety via {@code Idempotency-Key}.
 *
 * <p>Nearly every test here asserts the <em>charge count</em>, because that is
 * the only assertion that distinguishes "deduplicated the response" from
 * "deduplicated the charge". A cache in front of the endpoint would pass every
 * other assertion while taking money twice.
 */
class PurchaseIdempotencyTest {

    private static final UUID MONTHLY = SeedData.MONTHLY_UNLIMITED_ID;
    private static final UUID TEN_PACK = SeedData.TEN_CLASS_PACK_ID;
    private static final UUID PROMO = SeedData.LAUNCH_PROMO_PACK_ID;

    @Test
    @DisplayName("without a key the behaviour is exactly as before")
    void absentKeyBehavesExactlyAsBefore() {
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);
        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);

        assertThat(f.gateway().charges())
                .as("no key means no deduplication")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a blank key is treated as no key at all")
    void blankKeyIsIgnored() {
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "   ");
        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "");

        assertThat(f.gateway().charges()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same key replays the original purchase and charges once")
    void sameKeyReplaysTheOriginalPurchase() {
        TestStores.Fixture f = TestStores.succeeding();

        PurchaseOutcome first = f.service()
                .purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1");
        PurchaseOutcome second = f.service()
                .purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1");

        assertThat(second.purchase().id()).isEqualTo(first.purchase().id());
        assertThat(second.purchase()).isEqualTo(first.purchase());
        assertThat(f.gateway().charges())
                .as("the card was charged once, not twice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a replay is flagged so the caller can tell the difference")
    void replayIsFlagged() {
        TestStores.Fixture f = TestStores.succeeding();

        assertThat(f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1").replayed())
                .isFalse();
        assertThat(f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1").replayed())
                .isTrue();
    }

    @Test
    @DisplayName("different keys are independent")
    void differentKeysAreIndependent() {
        TestStores.Fixture f = TestStores.succeeding();

        PurchaseOutcome a = f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1");
        PurchaseOutcome b = f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-2");

        assertThat(b.purchase().id()).isNotEqualTo(a.purchase().id());
        assertThat(f.gateway().charges()).isEqualTo(2);
    }

    @Test
    @DisplayName("reusing a key with a different body is refused")
    void reusedKeyWithDifferentBodyIsRefused() {
        TestStores.Fixture f = TestStores.succeeding();
        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1");

        // Same key, different plan: silently replaying Alice's pack purchase
        // would be worse than an error, because the caller would believe it
        // bought something it did not.
        assertThatExceptionOfType(IdempotencyKeyReuseException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Alice", PROMO, TestStores.TODAY, "key-1"));

        // Same key, different member.
        assertThatExceptionOfType(IdempotencyKeyReuseException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Bob", TEN_PACK, TestStores.TODAY, "key-1"));

        // Same key, different date.
        assertThatExceptionOfType(IdempotencyKeyReuseException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Alice", TEN_PACK, TestStores.TODAY.plusDays(1), "key-1"));

        assertThat(f.gateway().charges()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed charge releases the key so an honest retry can succeed")
    void failedChargeReleasesTheKey() {
        // Without the release, a client retrying after a declined card would be
        // told its request is already in progress forever. This is the subtle
        // failure that putting the claim on the compensation stack fixes for
        // free.
        TestStores.Fixture declining = TestStores.with(CountingGateway.declining());

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> declining.service()
                        .purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1"));

        // Same key again, and it must not be stuck in progress.
        assertThatExceptionOfType(PaymentFailedException.class)
                .as("the retry reaches the gateway rather than being refused as in-progress")
                .isThrownBy(() -> declining.service()
                        .purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1"));

        assertThat(declining.gateway().charges())
                .as("both attempts genuinely tried to charge")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("500 concurrent requests with one key charge exactly once")
    void concurrentSameKeyChargesOnce() {
        TestStores.Fixture f = TestStores.succeeding();

        List<Outcome<PurchaseOutcome>> outcomes = Concurrency.raceAll(500, () ->
                f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1"));

        assertThat(f.gateway().charges())
                .as("one charge however many callers raced")
                .isEqualTo(1);

        // Whoever did not create it either replayed it or was told it was in
        // flight. No caller may get a second, different purchase.
        List<PurchaseOutcome> succeeded = Concurrency.successes(outcomes);
        assertThat(succeeded.stream().filter(o -> !o.replayed()).toList())
                .as("exactly one caller created the purchase")
                .hasSize(1);
        assertThat(succeeded.stream().map(o -> o.purchase().id()).distinct().toList())
                .as("every successful caller saw the same purchase")
                .hasSize(1);
        assertThat(Concurrency.failures(outcomes))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(IdempotentRequestInProgressException.class));
    }

    @Test
    @DisplayName("a key does not let a member bypass the one-unlimited rule")
    void keyDoesNotBypassBusinessRules() {
        // The rules run before and independently of the key machinery; this
        // guards against the dedup path becoming a side door around them.
        TestStores.Fixture f = TestStores.succeeding();
        f.service().purchase("Alice", MONTHLY, TestStores.TODAY, "key-1");

        assertThatExceptionOfType(UnlimitedMembershipExistsException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Alice", MONTHLY, TestStores.TODAY, "key-2"));

        assertThat(f.gateway().charges()).isEqualTo(1);
    }

    @Test
    @DisplayName("a key does not reserve promo inventory twice")
    void keyedReplayDoesNotDecrementInventoryTwice() {
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", PROMO, TestStores.TODAY, "key-1");
        f.service().purchase("Alice", PROMO, TestStores.TODAY, "key-1");

        assertThat(f.inventory().remaining(PROMO))
                .as("the replay consumed no slot")
                .hasValue(4);
    }

    @Test
    @DisplayName("a rejected request does not burn its key")
    void rejectedRequestDoesNotBurnTheKey() {
        // An inactive plan is rejected before the key is ever claimed, so the
        // same key stays usable for a corrected request.
        TestStores.Fixture f = TestStores.succeeding();

        assertThatExceptionOfType(PlanInactiveException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Alice", SeedData.LEGACY_PLAN_ID, TestStores.TODAY, "key-1"));

        assertThat(f.service().purchase("Alice", TEN_PACK, TestStores.TODAY, "key-1").replayed())
                .as("the key was never claimed, so this is a fresh purchase")
                .isFalse();
    }
}
