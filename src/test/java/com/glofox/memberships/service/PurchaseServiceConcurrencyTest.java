package com.glofox.memberships.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.Concurrency;
import com.glofox.memberships.Concurrency.Outcome;
import com.glofox.memberships.TestStores;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.payment.CountingGateway;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The highest-risk behaviour in the exercise: what happens when many people
 * want the last promo slot at the same moment.
 *
 * <p>Every assertion is on an <em>invariant</em> rather than an observed
 * schedule, so even a fully serialised run still verifies correctness and the
 * suite cannot go flaky in the direction of a false failure. Fresh stores per
 * test, no sleeps, and no clock control is needed — a consequence of the
 * status-only active rule, which is this design's quiet payoff: had the rule
 * been date-windowed, the hardest tests in the suite would also be the most
 * fragile.
 *
 * <p>Stated plainly, because it is the honest position: a <em>passing</em>
 * concurrency test is weak evidence. It shows no interleaving was hit that
 * breaks the invariant, not that none exists. Which is why these were also run
 * against deliberately broken stores — see the step 07 notes.
 */
class PurchaseServiceConcurrencyTest {

    private static final UUID PROMO = SeedData.LAUNCH_PROMO_PACK_ID;
    private static final UUID MONTHLY = SeedData.MONTHLY_UNLIMITED_ID;
    private static final int CONTENDERS = 500;

    private static LocalDate tomorrow() {
        return TestStores.TODAY.plusDays(1);
    }

    @Test
    @DisplayName("500 contenders for 5 promo slots: exactly 5 win and 5 cards are charged")
    void promoPackSellsExactlyFiveTimesUnder500Contenders() {
        TestStores.Fixture f = TestStores.succeeding();

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                // Distinct names on purpose: the promo pack is a PACK, so the
                // unlimited claim is not involved and this test contends on
                // inventory only. A failure then localises to one primitive.
                f.service().purchase("member-" + UUID.randomUUID(), PROMO, tomorrow()));

        List<Purchase> wins = Concurrency.successes(outcomes);
        List<Throwable> losses = Concurrency.failures(outcomes);

        // Five invariants, each catching a different wrong implementation.
        assertThat(wins).as("exactly the advertised five").hasSize(5);
        assertThat(losses).as("and everyone else is told why").hasSize(CONTENDERS - 5)
                .allSatisfy(failure ->
                        assertThat(failure).isInstanceOf(PlanSoldOutException.class));
        assertThat(f.inventory().remaining(PROMO))
                .as("the counter settles at zero, never below")
                .hasValue(0);
        assertThat(f.gateway().charges())
                .as("five cards charged, not 500 - the assertion that catches "
                    + "charging everyone and persisting five")
                .isEqualTo(5);
        assertThat(f.gateway().amounts())
                .as("and at the promo price, not another plan's")
                .containsOnly(2_500);
    }

    @RepeatedTest(10)
    @DisplayName("500 simultaneous attempts by one member yield exactly one unlimited membership")
    void sameMemberWinsExactlyOneUnlimitedMembership() {
        // The race the brief never mentions. It has precisely the same
        // check-then-act shape as the promo counter.
        //
        // Repeated ten times, and the repetition count is evidence-driven rather
        // than superstitious. Mutation testing measured this as the weakest
        // detection in the suite: replacing putIfAbsent with containsKey-then-put
        // was caught in only 9 of 12 suite runs, against 5 of 5 for the other two
        // mutants. Ten samples per run turn roughly even odds per attempt into
        // near-certainty, and the whole test costs ~50ms.
        //
        // Fresh stores per repetition, because the fixture is built inside the
        // method body - a shared claim store would make every repetition after
        // the first assert nothing.
        TestStores.Fixture f = TestStores.succeeding();

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                f.service().purchase("Alice", MONTHLY, tomorrow()));

        assertThat(Concurrency.successes(outcomes)).hasSize(1);
        assertThat(Concurrency.failures(outcomes)).hasSize(CONTENDERS - 1)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(UnlimitedMembershipExistsException.class));
        assertThat(f.gateway().charges())
                .as("one card charged, not 500")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("case and whitespace variants still yield one membership under contention")
    void caseAndWhitespaceVariantsStillYieldOneMembership() {
        // Proves the normalisation holds where it matters. The claim is that
        // MemberKey.equals plus putIfAbsent ARE the rule; this tests that claim
        // under load rather than in isolation.
        TestStores.Fixture f = TestStores.succeeding();
        String[] variants = {"Alice", "alice", "ALICE", "  Alice  ", " alice"};

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () -> {
            String variant = variants[(int) (Thread.currentThread().threadId() % variants.length)];
            return f.service().purchase(variant, MONTHLY, tomorrow());
        });

        assertThat(Concurrency.successes(outcomes)).hasSize(1);
        assertThat(f.gateway().charges()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 different members do not contend with each other")
    void differentMembersDoNotContend() {
        // The test that distinguishes "correct" from "merely serialised". A
        // single global lock would pass the one-unlimited test while making the
        // endpoint useless; this fails under one.
        TestStores.Fixture f = TestStores.succeeding();

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                f.service().purchase("member-" + UUID.randomUUID(), MONTHLY, tomorrow()));

        assertThat(Concurrency.successes(outcomes))
                .as("the claim is keyed per member, not held globally")
                .hasSize(CONTENDERS);
        assertThat(f.gateway().charges()).isEqualTo(CONTENDERS);
    }

    @Test
    @DisplayName("500 concurrent failed charges leak no inventory")
    void failedChargesUnderLoadLeakNoInventory() {
        // The compensation stack under load: 500 reserve-then-release cycles
        // must leave the counter exactly where it started.
        TestStores.Fixture f = TestStores.with(CountingGateway.declining());

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                f.service().purchase("member-" + UUID.randomUUID(), PROMO, tomorrow()));

        assertThat(Concurrency.successes(outcomes)).isEmpty();
        assertThat(f.inventory().remaining(PROMO))
                .as("every reserved slot was returned")
                .hasValue(5);

        // Note what is NOT asserted here: that only five charges were attempted.
        // Because a failed charge returns its slot immediately, the five slots
        // are recycled continuously and most contenders do get one in turn - a
        // run typically shows ~490 charge attempts, all declined. That is
        // correct behaviour, and asserting "5" here would have been a wrong
        // mental model baked into a test. The invariant that matters is that
        // every slot taken was given back.
        assertThat(f.gateway().charges())
                .as("a charge was attempted only by attempts that held a slot")
                .isBetween(1, CONTENDERS);
    }

    @Test
    @DisplayName("500 concurrent failed charges leak no unlimited claims")
    void failedChargesUnderLoadLeakNoClaims() {
        TestStores.Fixture f = TestStores.with(CountingGateway.declining());

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                f.service().purchase("Alice", MONTHLY, tomorrow()));

        assertThat(Concurrency.successes(outcomes)).isEmpty();

        // The claim must be free afterwards: a leak here bars the member
        // permanently, and no inventory assertion would ever notice because
        // unlimited plans have no counter.
        TestStores.Fixture working = TestStores.succeeding();
        assertThat(working.service().purchase("Alice", MONTHLY, tomorrow())).isNotNull();
        assertThat(f.claims().tryClaim(
                new com.glofox.memberships.domain.MemberKey("Alice"), UUID.randomUUID()))
                .as("the claim was released by whoever held it")
                .isTrue();
    }

    @Test
    @DisplayName("under a flaky gateway, successes plus remaining always equals five")
    void successesPlusRemainingIsInvariant() {
        // A property rather than a fixed count: this holds for EVERY
        // interleaving and every failure ratio, which makes it a stronger
        // statement than any specific number could be.
        TestStores.Fixture f = TestStores.with(CountingGateway.failingEveryOtherCall());

        List<Outcome<Purchase>> outcomes = Concurrency.raceAll(CONTENDERS, () ->
                f.service().purchase("member-" + UUID.randomUUID(), PROMO, tomorrow()));

        int wins = Concurrency.successes(outcomes).size();
        int remaining = f.inventory().remaining(PROMO).orElseThrow();

        assertThat(wins + remaining)
                .as("%d sold + %d left must always be the 5 that existed", wins, remaining)
                .isEqualTo(5);
        assertThat(remaining).isNotNegative();
    }
}
