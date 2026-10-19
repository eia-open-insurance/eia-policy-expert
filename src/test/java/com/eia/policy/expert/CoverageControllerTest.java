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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CoverageController.class)
class CoverageControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID COVERAGE_ID = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final UUID LIMIT_ID = UUID.fromString("88888888-8888-7888-8888-888888888888");
    private static final UUID CLAIM_ID = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID ALLOCATION_ID = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CoverageService service;

    @Test
    void listsCoveragesWithTheirLimits() throws Exception {
        when(service.listCoverages(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(view()), 0, 20, 1,
                        new ItemPage.Links(new ItemPage.Link("/internal/v1/policies/" + POLICY_ID
                                + "/coverages?tenantId=" + TENANT + "&page=0&size=20"), null, null)));

        mvc.perform(get("/internal/v1/policies/{policyId}/coverages", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].coverage.coverageCode").value("GL_PRODUCTS"))
                .andExpect(jsonPath("$.items[0].limit.aggregateAmount").value("5000000.0000"))
                .andExpect(jsonPath("$.items[0].state.remainingAmount").value("4700000.0000"));
    }

    @Test
    void createsACoverageWithEtagAndIdempotencyHeaders() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateCoverageRequest.class),
                eq("\"4\""), eq("key-1")))
                .thenReturn(new CoverageResult(view(), 5, false));

        mvc.perform(post("/internal/v1/policies/{policyId}/coverages", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"4\"")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.coverage.coverageCode").value("GL_PRODUCTS"));

        verify(service).create(eq(TENANT), eq(POLICY_ID), any(CreateCoverageRequest.class),
                eq("\"4\""), eq("key-1"));
    }

    @Test
    void replaysAnIdempotentCoverageCreateWithOkStatus() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateCoverageRequest.class), eq(null),
                eq("key-1"))).thenReturn(new CoverageResult(view(), 5, true));

        mvc.perform(post("/internal/v1/policies/{policyId}/coverages", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("ETag", "\"5\""));
    }

    @Test
    void validatesTheCoverageBody() throws Exception {
        mvc.perform(post("/internal/v1/policies/{policyId}/coverages", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coverageCode":"GL_PRODUCTS","coverageName":"Products",
                                 "coveragePart":"A","occurrenceBasis":"OCCURRENCE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "limit":{"perOccurrenceAmount":0,"currency":"USD"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("limit.perOccurrenceAmount"));
    }

    @Test
    void surfacesALimitExceededBusinessRuleWithAvailableAmount() throws Exception {
        when(service.applyErosion(eq(TENANT), eq(POLICY_ID), any(ErosionRequest.class), eq(null)))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "LIMIT_EXCEEDED", "The erosion fact would exceed the limit.",
                        List.of(new ApiException.FieldIssue("paidAmount", "LIMIT_EXCEEDED",
                                "exceeds")),
                        Map.of("availableAmount", "1875000.0000", "currency", "USD")));

        mvc.perform(post("/internal/v1/policies/{policyId}/erosion", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(erosion())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.category").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.meta.availableAmount").value("1875000.0000"));
    }

    @Test
    void appliesAnErosionFact() throws Exception {
        when(service.applyErosion(eq(TENANT), eq(POLICY_ID), any(ErosionRequest.class), eq("key-2")))
                .thenReturn(new ErosionResult(state(), 6, false));

        mvc.perform(post("/internal/v1/policies/{policyId}/erosion", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(erosion())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.remainingAmount").value("4700000.0000"));
    }

    @Test
    void listsLimitsAndAggregateState() throws Exception {
        when(service.listLimits(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(limit()), 0, 20, 1, null));
        when(service.aggregateState(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(state()), 0, 20, 1, null));

        mvc.perform(get("/internal/v1/policies/{policyId}/limits", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].limitId").doesNotExist())
                .andExpect(jsonPath("$.items[0].currency").value("USD"));

        mvc.perform(get("/internal/v1/policies/{policyId}/aggregate-state", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remainingAmount").value("4700000.0000"));
    }

    private static CreateCoverageRequest request() {
        return new CreateCoverageRequest("GL_PRODUCTS", "Products liability", "A", "OCCURRENCE",
                FROM, TO, new BigDecimal("0.10"),
                new CreateCoverageRequest.LimitSpec(new BigDecimal("2000000"),
                        new BigDecimal("5000000"), null, new BigDecimal("1000000"),
                        new BigDecimal("5000"), new BigDecimal("0.10"), "USD", null));
    }

    private static ErosionRequest erosion() {
        return new ErosionRequest(LIMIT_ID, CLAIM_ID, ALLOCATION_ID, 1L,
                new BigDecimal("250000"), new BigDecimal("50000"), BigDecimal.ZERO,
                Instant.parse("2026-10-19T12:00:00Z"));
    }

    private static CoverageView view() {
        return new CoverageView(coverage(), limit(), state());
    }

    private static PolicyCoverage coverage() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyCoverage(COVERAGE_ID, TENANT, POLICY_ID, "GL_PRODUCTS",
                "Products liability", "A", "OCCURRENCE", new BigDecimal("0.10"), FROM, TO,
                "ACTIVE", 1, now);
    }

    private static PolicyLimit limit() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyLimit(LIMIT_ID, TENANT, POLICY_ID, COVERAGE_ID,
                new BigDecimal("2000000.0000"), new BigDecimal("5000000.0000"), "PER_COVERAGE",
                new BigDecimal("1000000.0000"), new BigDecimal("5000.0000"),
                new BigDecimal("0.10"), "USD", "PAID_PLUS_RESERVED", FROM, TO, 1, now);
    }

    private static PolicyLimitState state() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new PolicyLimitState(UUID.randomUUID(), TENANT, POLICY_ID, LIMIT_ID, now,
                new BigDecimal("5000000.0000"), new BigDecimal("250000.0000"),
                new BigDecimal("50000.0000"), new BigDecimal("300000.0000"),
                BigDecimal.ZERO.setScale(4), new BigDecimal("4700000.0000"),
                new BigDecimal("0.060000"), BigDecimal.ZERO.setScale(4), 1L, now, "CURRENT", 1);
    }
}
