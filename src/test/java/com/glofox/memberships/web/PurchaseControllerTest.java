package com.glofox.memberships.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.domain.PlanSnapshot;
import com.glofox.memberships.domain.PlanType;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import com.glofox.memberships.payment.PaymentGateway.PaymentDeclinedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentGatewayUnavailableException;
import com.glofox.memberships.service.BlankMemberNameException;
import com.glofox.memberships.service.PlanInactiveException;
import com.glofox.memberships.service.PlanNotFoundException;
import com.glofox.memberships.service.PlanSoldOutException;
import com.glofox.memberships.service.PurchaseNotFoundException;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.service.StartDateInPastException;
import com.glofox.memberships.service.UnlimitedMembershipExistsException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract: success shape, and every row of the error table.
 *
 * <p>The service is mocked here deliberately — the rules themselves are proven
 * against real stores in the service tests, so this slice only has to prove the
 * translation from exception to status, code and body.
 */
@WebMvcTest(PurchaseController.class)
class PurchaseControllerTest {

    private static final UUID PLAN_ID = SeedData.MONTHLY_UNLIMITED_ID;
    private static final UUID PURCHASE_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_BODY = """
            {"member_name":"Alice",
             "plan_id":"11111111-1111-1111-1111-111111111111",
             "start_date":"2026-12-01"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PurchaseService service;

    private static Purchase samplePurchase() {
        Plan plan = new Plan(PLAN_ID, "Monthly Unlimited", PlanType.UNLIMITED, true, 8_000, null);
        return new Purchase(PURCHASE_ID, "Alice", new MemberKey("Alice"),
                            PlanSnapshot.of(plan), 8_000, PurchaseStatus.ACTIVE,
                            LocalDate.of(2026, 12, 1), "txn-123",
                            Instant.parse("2026-10-31T00:00:00Z"));
    }

    // ----- success ---------------------------------------------------------

    @Test
    @DisplayName("a valid purchase returns 201 with a Location header and the full payload")
    void createReturns201() throws Exception {
        given(service.purchase(eq("Alice"), eq(PLAN_ID), eq(LocalDate.of(2026, 12, 1))))
                .willReturn(samplePurchase());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/v1/memberships/purchases/" + PURCHASE_ID))
                .andExpect(jsonPath("$.id").value(PURCHASE_ID.toString()))
                .andExpect(jsonPath("$.member_name").value("Alice"))
                .andExpect(jsonPath("$.amount_cents").value(8000))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.start_date").value("2026-12-01"))
                .andExpect(jsonPath("$.transaction_id").value("txn-123"))
                .andExpect(jsonPath("$.plan.plan_id").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.plan.type").value("unlimited"))
                .andExpect(jsonPath("$.plan.price_cents").value(8000));
    }

    @Test
    @DisplayName("the internal member key never appears on the wire")
    void memberKeyIsNotExposed() throws Exception {
        given(service.purchase(any(), any(), any())).willReturn(samplePurchase());

        String body = mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("member_key").doesNotContain("memberKey");
    }

    @Test
    @DisplayName("a purchase can be fetched by id")
    void getReturnsPurchase() throws Exception {
        given(service.getById(PURCHASE_ID)).willReturn(samplePurchase());

        mvc.perform(get("/v1/memberships/purchases/" + PURCHASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PURCHASE_ID.toString()))
                .andExpect(jsonPath("$.amount_cents").value(8000));
    }

    // ----- the error table, row by row -------------------------------------

    @Test
    @DisplayName("unknown plan maps to 404 plan_not_found")
    void unknownPlan() throws Exception {
        willThrow(new PlanNotFoundException(PLAN_ID))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("plan_not_found"))
                .andExpect(jsonPath("$.type").value("urn:glofox:problem:plan-not-found"))
                .andExpect(jsonPath("$.instance").value("/v1/memberships/purchases"));
    }

    @Test
    @DisplayName("inactive plan maps to 409 plan_inactive")
    void inactivePlan() throws Exception {
        Plan legacy = new Plan(SeedData.LEGACY_PLAN_ID, "Legacy Plan",
                               PlanType.UNLIMITED, false, 6_000, null);
        willThrow(new PlanInactiveException(legacy))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plan_inactive"));
    }

    @Test
    @DisplayName("sold out maps to 409 plan_sold_out")
    void soldOut() throws Exception {
        Plan promo = new Plan(SeedData.LAUNCH_PROMO_PACK_ID, "Launch Promo Pack",
                              PlanType.PACK, true, 2_500, 5);
        willThrow(new PlanSoldOutException(promo))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plan_sold_out"));
    }

    @Test
    @DisplayName("a membership conflict is 409 and does not echo the member name")
    void membershipConflictDoesNotEchoTheMemberName() throws Exception {
        // Echoing the name would make this an unauthenticated membership-existence
        // oracle: probe a name, learn whether that person is a member here.
        willThrow(new UnlimitedMembershipExistsException(new MemberKey("Alice")))
                .given(service).purchase(any(), any(), any());

        String body = mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unlimited_membership_exists"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Alice").doesNotContain("alice");
    }

    @Test
    @DisplayName("a declined charge maps to 402 payment_declined")
    void paymentDeclined() throws Exception {
        willThrow(new PaymentDeclinedException("card declined"))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("payment_declined"));
    }

    @Test
    @DisplayName("a gateway outage maps to 502 payment_gateway_error, distinctly from a decline")
    void gatewayUnavailable() throws Exception {
        willThrow(new PaymentGatewayUnavailableException("timeout"))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment_gateway_error"));
    }

    @Test
    @DisplayName("a blank name reaching the service maps to 400 validation_failed")
    void blankName() throws Exception {
        willThrow(new BlankMemberNameException())
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content("""
                                {"member_name":"  ",
                                 "plan_id":"11111111-1111-1111-1111-111111111111",
                                 "start_date":"2026-12-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    @DisplayName("a past start date maps to 400 start_date_in_past, distinct from validation_failed")
    void pastStartDate() throws Exception {
        willThrow(new StartDateInPastException(LocalDate.of(2020, 1, 1)))
                .given(service).purchase(any(), any(), any());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("start_date_in_past"));
    }

    @Test
    @DisplayName("unknown purchase id maps to 404 purchase_not_found")
    void unknownPurchase() throws Exception {
        UUID unknown = UUID.fromString("99999999-9999-9999-9999-999999999999");
        willThrow(new PurchaseNotFoundException(unknown)).given(service).getById(unknown);

        mvc.perform(get("/v1/memberships/purchases/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("purchase_not_found"));
    }

    // ----- shape validation, before the service is reached ------------------

    @Test
    @DisplayName("missing fields are reported together, and the service is not called")
    void missingFieldsAreBatched() throws Exception {
        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "member_name", "plan_id", "start_date")));

        verify(service, never()).purchase(any(), any(), any());
    }

    @Test
    @DisplayName("the member name length is bounded at 100 characters")
    void memberNameLengthIsBounded() throws Exception {
        given(service.purchase(any(), any(), any())).willReturn(samplePurchase());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content(bodyWithName("a".repeat(100))))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content(bodyWithName("a".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("member_name"));
    }

    @Test
    @DisplayName("a malformed body is 400 validation_failed and leaks no parser internals")
    void malformedBody() throws Exception {
        String body = mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("com.").doesNotContain("Exception");
    }

    @Test
    @DisplayName("an unparseable date is rejected during binding, not by a parsing branch")
    void unparseableDate() throws Exception {
        mvc.perform(post("/v1/memberships/purchases")
                        .contentType("application/json")
                        .content("""
                                {"member_name":"Alice",
                                 "plan_id":"11111111-1111-1111-1111-111111111111",
                                 "start_date":"2026-13-45"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        verify(service, never()).purchase(any(), any(), any());
    }

    @Test
    @DisplayName("a non-UUID path id is 400, not 404 - it could never name a purchase")
    void nonUuidPathIsBadRequest() throws Exception {
        mvc.perform(get("/v1/memberships/purchases/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    private static String bodyWithName(String name) {
        return """
                {"member_name":"%s",
                 "plan_id":"11111111-1111-1111-1111-111111111111",
                 "start_date":"2026-12-01"}
                """.formatted(name);
    }
}
