package com.glofox.memberships.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.TestStores;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.PlanType;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import com.glofox.memberships.payment.CountingGateway;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The six business rules, exercised against real stores with a fixed clock and
 * no Spring context.
 *
 * <p>Several tests assert on {@code gateway.charges()} as well as on state. That
 * is deliberate: the ordering requirement — never charge for a request that is
 * already doomed — is only observable through the charge count.
 */
class PurchaseServiceRulesTest {

    private static final UUID MONTHLY = SeedData.MONTHLY_UNLIMITED_ID;
    private static final UUID TEN_PACK = SeedData.TEN_CLASS_PACK_ID;
    private static final UUID PROMO = SeedData.LAUNCH_PROMO_PACK_ID;
    private static final UUID LEGACY = SeedData.LEGACY_PLAN_ID;

    @Test
    @DisplayName("an unknown plan is rejected and the gateway is never called")
    void unknownPlanRejected() {
        TestStores.Fixture f = TestStores.succeeding();
        UUID unknown = UUID.randomUUID();

        assertThatExceptionOfType(PlanNotFoundException.class)
                .isThrownBy(() -> f.service().purchase("Alice", unknown, TestStores.TODAY));

        assertThat(f.gateway().charges()).as("nothing may be charged").isZero();
    }

    @Test
    @DisplayName("an inactive plan cannot be purchased and is not charged for")
    void inactivePlanRejected() {
        TestStores.Fixture f = TestStores.succeeding();

        assertThatExceptionOfType(PlanInactiveException.class)
                .isThrownBy(() -> f.service().purchase("Alice", LEGACY, TestStores.TODAY));

        assertThat(f.gateway().charges()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    @DisplayName("a blank member name is rejected before anything else happens")
    void blankMemberNameRejected(String blank) {
        TestStores.Fixture f = TestStores.succeeding();

        assertThatExceptionOfType(BlankMemberNameException.class)
                .isThrownBy(() -> f.service().purchase(blank, MONTHLY, TestStores.TODAY));

        assertThat(f.gateway().charges()).isZero();
    }

    @Test
    @DisplayName("a null member name is rejected as blank, not as a NullPointerException")
    void nullMemberNameRejected() {
        TestStores.Fixture f = TestStores.succeeding();

        assertThatExceptionOfType(BlankMemberNameException.class)
                .isThrownBy(() -> f.service().purchase(null, MONTHLY, TestStores.TODAY));
    }

    @Test
    @DisplayName("yesterday is rejected but today and tomorrow are accepted")
    void pastStartDateRejected() {
        TestStores.Fixture f = TestStores.succeeding();

        assertThatExceptionOfType(StartDateInPastException.class)
                .isThrownBy(() -> f.service()
                        .purchase("Alice", TEN_PACK, TestStores.TODAY.minusDays(1)));

        // Both boundaries, because "not in the past" is an inclusive bound and
        // an off-by-one here would reject every same-day purchase.
        assertThat(f.service().purchase("Bob", TEN_PACK, TestStores.TODAY)).isNotNull();
        assertThat(f.service().purchase("Carol", TEN_PACK, TestStores.TODAY.plusDays(1)))
                .isNotNull();
        assertThat(f.gateway().charges()).isEqualTo(2);
    }

    @Test
    @DisplayName("a plan with no inventory counter can be purchased repeatedly")
    void unlimitedInventoryPlanBuysRepeatedly() {
        // Guards the highest-probability one-character bug in the exercise:
        // writing the sold-out check as remaining > 0, which would refuse every
        // plan that has no inventory limit at all.
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);
        f.service().purchase("Bob", TEN_PACK, TestStores.TODAY);
        f.service().purchase("Carol", TEN_PACK, TestStores.TODAY);

        assertThat(f.gateway().charges()).isEqualTo(3);
        assertThat(f.inventory().remaining(TEN_PACK))
                .as("still no counter; unlimited means absent, not large")
                .isEmpty();
    }

    @Test
    @DisplayName("a member may hold only one active unlimited membership")
    void secondUnlimitedSameMemberRejected() {
        TestStores.Fixture f = TestStores.succeeding();
        f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThatExceptionOfType(UnlimitedMembershipExistsException.class)
                .isThrownBy(() -> f.service().purchase("Alice", MONTHLY, TestStores.TODAY));

        assertThat(f.gateway().charges()).as("the second attempt is not charged").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "ALICE", "  Alice  ", "aLiCe"})
    @DisplayName("the one-unlimited rule survives case and whitespace variation")
    void unlimitedRuleIsCaseAndWhitespaceInsensitive(String variant) {
        TestStores.Fixture f = TestStores.succeeding();
        f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThatExceptionOfType(UnlimitedMembershipExistsException.class)
                .isThrownBy(() -> f.service().purchase(variant, MONTHLY, TestStores.TODAY));
    }

    @Test
    @DisplayName("holding a pack does not block an unlimited membership")
    void packsDoNotBlockUnlimited() {
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);
        Purchase unlimited = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThat(unlimited.plan().type()).isEqualTo(PlanType.UNLIMITED);
        assertThat(f.gateway().charges()).isEqualTo(2);
    }

    @Test
    @DisplayName("a member may hold several packs")
    void multiplePacksAllowed() {
        TestStores.Fixture f = TestStores.succeeding();

        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);
        f.service().purchase("Alice", TEN_PACK, TestStores.TODAY);

        assertThat(f.gateway().charges()).isEqualTo(2);
    }

    @Test
    @DisplayName("a promo purchase decrements inventory by exactly one")
    void promoDecrementsByExactlyOne() {
        TestStores.Fixture f = TestStores.succeeding();
        assertThat(f.inventory().remaining(PROMO)).hasValue(5);

        f.service().purchase("Alice", PROMO, TestStores.TODAY);

        assertThat(f.inventory().remaining(PROMO)).hasValue(4);
    }

    @Test
    @DisplayName("the promo pack sells out after five and the sixth is not charged")
    void promoSoldOutAfterFive() {
        TestStores.Fixture f = TestStores.succeeding();

        for (int i = 0; i < 5; i++) {
            f.service().purchase("Member" + i, PROMO, TestStores.TODAY);
        }

        assertThatExceptionOfType(PlanSoldOutException.class)
                .isThrownBy(() -> f.service().purchase("Member5", PROMO, TestStores.TODAY));

        // Five, not six. The ordering requirement made observable: a doomed
        // request must not reach the gateway.
        assertThat(f.gateway().charges()).isEqualTo(5);
        assertThat(f.inventory().remaining(PROMO)).hasValue(0);
    }

    @Test
    @DisplayName("a purchase snapshots the plan and records the amount charged")
    void purchaseSnapshotsPlanAndAmount() {
        TestStores.Fixture f = TestStores.succeeding();

        Purchase purchase = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThat(purchase.amountCents()).isEqualTo(8_000);
        assertThat(f.gateway().amounts())
                .as("the amount recorded is the amount charged")
                .containsExactly(8_000);
        assertThat(purchase.plan().planId()).isEqualTo(MONTHLY);
        assertThat(purchase.plan().name()).isEqualTo("Monthly Unlimited");
        assertThat(purchase.plan().type()).isEqualTo(PlanType.UNLIMITED);
        assertThat(purchase.plan().priceCents()).isEqualTo(8_000);
        assertThat(purchase.plan().credits()).isNull();
        assertThat(purchase.status()).isEqualTo(PurchaseStatus.ACTIVE);
        assertThat(purchase.transactionId()).isNotBlank();
        assertThat(purchase.createdAt())
                .as("stamped from the injected clock, so it is assertable")
                .isEqualTo(TestStores.fixedInstant());
    }

    @Test
    @DisplayName("the member name is stored as submitted but matched normalised")
    void storesNameAsSubmitted() {
        TestStores.Fixture f = TestStores.succeeding();

        Purchase purchase = f.service().purchase("  Alice  ", MONTHLY, TestStores.TODAY);

        assertThat(purchase.memberName()).isEqualTo("  Alice  ");
        assertThat(purchase.memberKey().value()).isEqualTo("alice");
    }

    @Test
    @DisplayName("a saved purchase is retrievable by id, and an unknown id throws")
    void purchaseRoundTrip() {
        TestStores.Fixture f = TestStores.succeeding();
        Purchase saved = f.service().purchase("Alice", MONTHLY, TestStores.TODAY);

        assertThat(f.service().getById(saved.id())).isEqualTo(saved);

        UUID unknown = UUID.randomUUID();
        assertThatExceptionOfType(PurchaseNotFoundException.class)
                .isThrownBy(() -> f.service().getById(unknown));
    }

    @Test
    @DisplayName("a sold-out promo attempt does not consume the member's unlimited slot")
    void soldOutDoesNotStrandUnlimitedClaim() {
        // The failure a payment-only catch block would hide. The promo pack is a
        // PACK, so no unlimited claim is taken here - but the member must still
        // be able to buy an unlimited plan afterwards, and the compensation path
        // is what guarantees nothing was left behind.
        TestStores.Fixture f = TestStores.with(CountingGateway.succeeding());
        for (int i = 0; i < 5; i++) {
            f.service().purchase("Member" + i, PROMO, TestStores.TODAY);
        }

        assertThatExceptionOfType(PlanSoldOutException.class)
                .isThrownBy(() -> f.service().purchase("Alice", PROMO, TestStores.TODAY));

        assertThat(f.service().purchase("Alice", MONTHLY, TestStores.TODAY)).isNotNull();
    }
}
