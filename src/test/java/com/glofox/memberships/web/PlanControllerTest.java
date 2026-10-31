package com.glofox.memberships.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The plans listing contract.
 *
 * <p>Uses the <strong>real seeded stores</strong> via {@code @Import} rather
 * than mocks. {@code @WebMvcTest} does not scan {@code @Configuration} classes,
 * so the store beans have to be supplied somehow; importing the real
 * {@link SeedingConfig} means this test exercises the actual seed mapping and
 * the actual {@code OptionalInt} to {@code null} composition. Mocking the
 * catalogue would reduce this to a test of the assembler, when the seeded data
 * is the thing the contract is about.
 */
@WebMvcTest(PlanController.class)
@Import(SeedingConfig.class)
class PlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("returns all four plans in seed order")
    void returnsFourPlansInSeedOrder() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$[1].id").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$[2].id").value("33333333-3333-3333-3333-333333333333"))
                .andExpect(jsonPath("$[3].id").value("44444444-4444-4444-4444-444444444444"));
    }

    @Test
    @DisplayName("includes the inactive plan rather than filtering it out")
    void includesInactivePlan() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[3].name").value("Legacy Plan"))
                .andExpect(jsonPath("$[3].active").value(false))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("plan type is the lowercase wire vocabulary, not the enum name")
    void typeIsLowercase() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[0].type").value("unlimited"))
                .andExpect(jsonPath("$[1].type").value("pack"));
    }

    @Test
    @DisplayName("field names are snake_case on the wire")
    void usesSnakeCase() throws Exception {
        // This assertion is load-bearing, not box-ticking. The naming strategy is
        // global configuration, which makes the wire contract invisible at the
        // DTO; this test is the agreed mitigation. The negative assertion matters
        // most: if the config is ever dropped, camelCase would still serialise
        // fine and only this check would notice.
        String body = mockMvc.perform(get("/v1/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("price_cents").contains("remaining_slots");
        assertThat(body).doesNotContain("priceCents").doesNotContain("remainingSlots");
    }

    @Test
    @DisplayName("null credits and slots are present as null, not omitted")
    void nullsArePresentNotOmitted() throws Exception {
        // "unlimited" and "field missing" must be distinguishable by a client, so
        // the key has to exist with a null value. jsonPath cannot tell an absent
        // key from a null one, so assert on the serialised form directly.
        String body = mockMvc.perform(get("/v1/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("Monthly Unlimited has no credits and no inventory limit")
                .contains("\"credits\":null")
                .contains("\"remaining_slots\":null");
    }

    @Test
    @DisplayName("the promo pack reports its finite remaining slots")
    void promoPackHasFiniteSlots() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[2].name").value("Launch Promo Pack"))
                .andExpect(jsonPath("$[2].remaining_slots").value(5))
                .andExpect(jsonPath("$[2].credits").value(5))
                .andExpect(jsonPath("$[2].price_cents").value(2500));
    }

    @Test
    @DisplayName("plans with unlimited inventory report null slots, never zero")
    void unlimitedInventoryIsNullNotZero() throws Exception {
        mockMvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[0].remaining_slots").doesNotExist())
                .andExpect(jsonPath("$[1].remaining_slots").doesNotExist());
    }
}
