package com.glofox.memberships;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.PaymentGateway.PaymentDeclinedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentResult;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Four tests through the whole stack, and deliberately only four.
 *
 * <p>Everything else is already covered more cheaply — re-asserting the
 * validation matrix here would buy nothing and cost context startup. The goal
 * is retired risk, not coverage.
 *
 * <p>One {@code @MockitoBean} stubbed per test rather than two classes with two
 * gateways: two classes would mean two cached contexts. This is the only place
 * in the suite where bean overriding is used at all; everywhere else
 * substitution is plain constructor injection.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseApiIntegrationTest {

    private static final String PROMO_ID = "33333333-3333-3333-3333-333333333333";
    private static final String MONTHLY_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentGateway gateway;

    private static String body(String memberName, String planId) {
        return """
                {"member_name":"%s","plan_id":"%s","start_date":"%s"}
                """.formatted(memberName, planId, LocalDate.now().plusDays(30));
    }

    @Test
    @DisplayName("a purchase can be created and then fetched at its Location")
    void purchaseThenFetchRoundTrip() throws Exception {
        given(gateway.charge(anyInt(), anyString()))
                .willReturn(new PaymentResult("txn-integration"));

        String location = mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content(body("Integration Alice", MONTHLY_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.amount_cents").value(8000))
                .andExpect(jsonPath("$.transaction_id").value("txn-integration"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member_name").value("Integration Alice"))
                .andExpect(jsonPath("$.amount_cents").value(8000))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.plan.name").value("Monthly Unlimited"));
    }

    @Test
    @DisplayName("the plans endpoint serves four seeded plans through the real stack")
    void plansEndpointServesFourSeededPlans() throws Exception {
        mvc.perform(get("/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].active").value(false));
    }

    @Test
    @DisplayName("a declined payment returns 402 and leaves no purchase and no inventory change")
    void declinedPaymentReturns402AndPersistsNothing() throws Exception {
        // Proves two things at once: that bean overriding works on this Spring
        // Boot version, and that the cleanup holds through the full stack rather
        // than only at service level.
        willThrow(new PaymentDeclinedException("card declined"))
                .given(gateway).charge(anyInt(), anyString());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content(body("Declined Dave", PROMO_ID)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("payment_declined"));

        // The promo pack's inventory is observable over the wire, so the
        // compensation is verifiable without reaching into a store.
        mvc.perform(get("/v1/plans"))
                .andExpect(jsonPath("$[2].name").value("Launch Promo Pack"))
                .andExpect(jsonPath("$[2].remaining_slots").value(5));
    }

    @Test
    @DisplayName("an unknown purchase id returns 404 purchase_not_found")
    void unknownPurchaseReturns404() throws Exception {
        mvc.perform(get("/v1/memberships/purchases/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("purchase_not_found"));
    }
}
