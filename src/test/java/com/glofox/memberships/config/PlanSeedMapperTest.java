package com.glofox.memberships.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.domain.PlanType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanSeedMapperTest {

    @Test
    @DisplayName("both known plan types map to their enum")
    void mapsBothTypes() {
        assertThat(PlanSeedMapper.parseType("unlimited")).isEqualTo(PlanType.UNLIMITED);
        assertThat(PlanSeedMapper.parseType("pack")).isEqualTo(PlanType.PACK);
    }

    @Test
    @DisplayName("an unrecognised plan type throws rather than defaulting")
    void unknownTypeThrows() {
        // This is the fail-fast case that cannot be reached through SeedData,
        // which is immutable vendor code. It is the reason this mapper is a
        // separate testable class instead of a private method on the config.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> PlanSeedMapper.parseType("subscription"))
                .withMessageContaining("subscription");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> PlanSeedMapper.parseType("UNLIMITED"))
                .as("matching is exact; the wire form is lowercase");
    }

    @Test
    @DisplayName("all four seeded plans map, and the legacy plan stays inactive")
    void mapsAllFourSeedPlans() {
        List<Plan> plans = SeedData.PLANS.stream().map(PlanSeedMapper::toPlan).toList();

        assertThat(plans).hasSize(4);
        assertThat(plans).extracting(Plan::name)
                .containsExactly("Monthly Unlimited", "10-Class Pack",
                                 "Launch Promo Pack", "Legacy Plan");

        Plan legacy = plans.get(3);
        assertThat(legacy.active()).isFalse();
        assertThat(legacy.type()).isEqualTo(PlanType.UNLIMITED);
        assertThat(legacy.priceCents()).isEqualTo(6_000);

        Plan promo = plans.get(2);
        assertThat(promo.credits()).isEqualTo(5);
        assertThat(promo.priceCents()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("credits stay null for unlimited plans - absence of meaning, not zero")
    void unlimitedPlansHaveNullCredits() {
        Plan monthly = PlanSeedMapper.toPlan(SeedData.PLANS.get(0));

        assertThat(monthly.type()).isEqualTo(PlanType.UNLIMITED);
        assertThat(monthly.credits()).isNull();
    }
}
