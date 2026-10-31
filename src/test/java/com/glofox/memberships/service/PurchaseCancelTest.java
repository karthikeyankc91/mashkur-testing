package com.glofox.memberships.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.Concurrency;
import com.glofox.memberships.Concurrency.Outcome;
import com.glofox.memberships.TestStores;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cancel, and the race that only the cancel path has. */
class PurchaseCancelTest {

    private static final UUID PROMO = SeedData.LAUNCH_PROMO_PACK_ID;
    private static final UUID MONTHLY = SeedData.MONTHLY_UNLIMITED_ID;
    private static final UUID TEN_PACK = SeedData.TEN_CLASS_PACK_ID;

    @Test
    @DisplayName("cancelling sets the status and returns the updated purchase")
    void cancelSetsStatusAndReturnsPurchase() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);

        Purchase cancelled = f.service().cancel(bought.id());

        assertThat(cancelled.status()).isEqualTo(PurchaseStatus.CANCELLED);
        assertThat(cancelled.id()).isEqualTo(bought.id());
        assertThat(cancelled.amountCents())
                .as("cancelling does not rewrite what was charged")
                .isEqualTo(bought.amountCents());
        assertThat(f.service().getById(bought.id()).status())
                .isEqualTo(PurchaseStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelling a promo purchase returns its slot")
    void cancelRestoresPromoInventory() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", PROMO, TestStores.TODAY);
        assertThat(f.inventory().remaining(PROMO)).hasValue(4);

        f.service().cancel(bought.id());

        assertThat(f.inventory().remaining(PROMO)).hasValue(5);
    }

    @Test
    @DisplayName("cancelling an unlimited membership frees the member's slot")
    void cancelReleasesUnlimitedClaim() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThatExceptionOfType(UnlimitedMembershipExistsException.class)
                .isThrownBy(() -> f.service().purchase("Alice", MONTHLY, TestStores.TODAY));

        f.service().cancel(bought.id());

        assertThat(f.service().purchase("Alice", MONTHLY, TestStores.TODAY))
                .as("the member may buy again after cancelling")
                .isNotNull();
    }

    @Test
    @DisplayName("cancelling twice is refused")
    void doubleCancelIsRefused() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", PROMO, TestStores.TODAY);

        f.service().cancel(bought.id());

        assertThatExceptionOfType(PurchaseAlreadyCancelledException.class)
                .isThrownBy(() -> f.service().cancel(bought.id()));
        assertThat(f.inventory().remaining(PROMO))
                .as("and the refused cancel restored nothing")
                .hasValue(5);
    }

    @Test
    @DisplayName("cancelling an unknown purchase throws rather than silently succeeding")
    void cancelUnknownPurchaseThrows() {
        TestStores.Fixture f = TestStores.succeeding();
        UUID unknown = UUID.randomUUID();

        assertThatExceptionOfType(PurchaseNotFoundException.class)
                .isThrownBy(() -> f.service().cancel(unknown));
    }

    @Test
    @DisplayName("cancelling a plan with no counter does not invent inventory")
    void cancelDoesNotRestoreUnlimitedInventory() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);

        f.service().cancel(bought.id());

        assertThat(f.inventory().remaining(TEN_PACK))
                .as("still no counter; release was a no-op")
                .isEmpty();
    }

    @Test
    @DisplayName("500 concurrent cancels of one purchase restore exactly one slot")
    void concurrentCancelsRestoreExactlyOneSlot() {
        // THE test for this step. Under restore-then-compare-and-set, every
        // caller would read ACTIVE, every caller would restore the slot, and
        // the counter would finish above 5 - overselling a five-slot promo
        // through the cancel endpoint.
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", PROMO, TestStores.TODAY);
        assertThat(f.inventory().remaining(PROMO)).hasValue(4);

        List<Outcome<Purchase>> outcomes =
                Concurrency.raceAll(500, () -> f.service().cancel(bought.id()));

        assertThat(Concurrency.successes(outcomes))
                .as("exactly one caller may cancel")
                .hasSize(1);
        assertThat(Concurrency.failures(outcomes))
                .hasSize(499)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(PurchaseAlreadyCancelledException.class));
        assertThat(f.inventory().remaining(PROMO))
                .as("the slot came back exactly once, not 500 times")
                .hasValue(5);
    }

    @Test
    @DisplayName("500 concurrent cancels of an unlimited membership release the claim once")
    void concurrentCancelsReleaseClaimOnce() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase bought = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        List<Outcome<Purchase>> outcomes =
                Concurrency.raceAll(500, () -> f.service().cancel(bought.id()));

        assertThat(Concurrency.successes(outcomes)).hasSize(1);

        // The claim is free, and crucially a NEW claim by the same member is not
        // then revoked by a straggler release - which is what the purchase id
        // argument on release() exists to prevent.
        Purchase second = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);
        assertThat(second).isNotNull();
        assertThat(f.claims().tryClaim(new MemberKey("Alice"), UUID.randomUUID()))
                .as("the second purchase still holds the claim")
                .isFalse();
    }
}
