package com.eia.policy.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the rating route: both path prefixes, the calculation-only
 * and applied responses, the ETag on an applied policy, tenant/book scope, and
 * problem mapping.
 */
@WebMvcTest(PolicyRatingController.class)
class PolicyRatingControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID WORKSHEET_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant CREATED_AT = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PolicyRatingService service;

    @Test
    void calculatesAWorksheetAndOmitsThePolicyWhenNotApplied() throws Exception {
        when(service.rate(eq(TENANT), eq(POLICY_ID), any(RatingRequest.class), isNull()))
                .thenReturn(new RatingResult(worksheet(), null));

        mvc.perform(post("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.worksheet.id").value(WORKSHEET_ID.toString()))
                .andExpect(jsonPath("$.worksheet.policyId").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.worksheet.ratingMethod").value("RATE_TIMES_EXPOSURE"))
                .andExpect(jsonPath("$.worksheet.baseRate").value("0.012500"))
                .andExpect(jsonPath("$.worksheet.exposureAmount").value("1000000.0000"))
                .andExpect(jsonPath("$.worksheet.factor").value("1.250000"))
                .andExpect(jsonPath("$.worksheet.computedPremium").value("15625.0000"))
                .andExpect(jsonPath("$.worksheet.currency").value("USD"))
                .andExpect(jsonPath("$.worksheet.calcHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.worksheet.status").value("CALCULATED"))
                .andExpect(jsonPath("$.policy").doesNotExist());
    }

    @Test
    void appliesTheRatingAndReturnsTheUpdatedPolicyWithItsETag() throws Exception {
        when(service.rate(eq(TENANT), eq(POLICY_ID), any(RatingRequest.class), eq("\"3\"")))
                .thenReturn(new RatingResult(worksheet(), policy(4)));

        mvc.perform(post("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(true))))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.worksheet.computedPremium").value("15625.0000"))
                .andExpect(jsonPath("$.policy.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.policy.premiumAmount").value("15625.0000"))
                .andExpect(jsonPath("$.policy.version").value(4));
    }

    @Test
    void keepsTheInternalAliasAndAcceptsTheTenantHeader() throws Exception {
        when(service.rate(eq(TENANT), eq(POLICY_ID), any(RatingRequest.class), isNull()))
                .thenReturn(new RatingResult(worksheet(), null));

        mvc.perform(post("/internal/v1/policies/{policyId}/rating", POLICY_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.worksheet.id").value(WORKSHEET_ID.toString()));
    }

    @Test
    void getsTheLatestWorksheet() throws Exception {
        when(service.latest(TENANT, POLICY_ID)).thenReturn(worksheet());

        mvc.perform(get("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(WORKSHEET_ID.toString()))
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.computedPremium").value("15625.0000"));
    }

    @Test
    void getsTheLatestWorksheetThroughTheInternalAliasAndTenantHeader() throws Exception {
        when(service.latest(TENANT, POLICY_ID)).thenReturn(worksheet());

        mvc.perform(get("/internal/v1/policies/{policyId}/rating", POLICY_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calcHash").value("a".repeat(64)));
    }

    @Test
    void returns404WhenThePolicyHasNoWorksheet() throws Exception {
        when(service.latest(TENANT, POLICY_ID)).thenThrow(PolicyErrors.notFound(
                "RATING_WORKSHEET_NOT_FOUND", "policyId", "No rating worksheet exists"));

        mvc.perform(get("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATING_WORKSHEET_NOT_FOUND"));
    }

    @Test
    void surfacesANonRateablePolicyAsA409Problem() throws Exception {
        when(service.rate(eq(TENANT), eq(POLICY_ID), any(RatingRequest.class), isNull()))
                .thenThrow(PolicyErrors.conflict("POLICY_NOT_RATEABLE", "status",
                        "Only DRAFT or QUOTED policies are rateable."));

        mvc.perform(post("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_RATEABLE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rejectsANegativeBaseRateAndFactorAtTheRequestBoundary() throws Exception {
        RatingRequest negativeBase = new RatingRequest("FLAT", new BigDecimal("-1"),
                null, null, false);
        mvc.perform(post("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeBase)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("baseRate"));

        RatingRequest negativeFactor = new RatingRequest("FLAT", new BigDecimal("1"),
                null, new BigDecimal("-0.5"), false);
        mvc.perform(post("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeFactor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("factor"));
    }

    @Test
    void requiresATenantAndRejectsANonDirectBook() throws Exception {
        mvc.perform(get("/api/v1/policies/{policyId}/rating", POLICY_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        mvc.perform(get("/api/v1/policies/{policyId}/rating", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    private static RatingRequest request(boolean apply) {
        return new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("0.0125"),
                new BigDecimal("1000000"), new BigDecimal("1.25"), apply);
    }

    private static PolicyRatingWorksheet worksheet() {
        return new PolicyRatingWorksheet(WORKSHEET_ID, TENANT, POLICY_ID,
                "RATE_TIMES_EXPOSURE", new BigDecimal("0.012500"),
                new BigDecimal("1000000.0000"), new BigDecimal("1.250000"),
                new BigDecimal("15625.0000"), "USD", "a".repeat(64), "CALCULATED",
                CREATED_AT, new UUID(0L, 0L));
    }

    private static Policy policy(int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "DRAFT",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal("15625.0000"), "USD", new BigDecimal("5000000.0000"), "USD",
                version, CREATED_AT, CREATED_AT);
    }
}
