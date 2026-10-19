package com.eia.policy.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PolicyController.class)
@Import(PolicyService.class)
class PolicyControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PolicyRepository repository;

    @Test
    void listsPoliciesWithDefaultPageEnvelope() throws Exception {
        when(repository.findPage(TENANT, 0, 20))
                .thenReturn(new PolicyPageData(List.of(policy("DRAFT", 0)), 1));

        mvc.perform(get("/internal/v1/policies").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].policyNumber").value("POL-2026-0000001"))
                .andExpect(jsonPath("$.items[0].premiumAmount").value("125000.0000"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$._links.self.href").value(
                        "/internal/v1/policies?tenantId=" + TENANT + "&page=0&size=20"))
                .andExpect(jsonPath("$._links.next").doesNotExist())
                .andExpect(jsonPath("$._links.prev").doesNotExist());
    }

    @Test
    void paginationLinksAtInteriorBoundary() throws Exception {
        when(repository.findPage(TENANT, 1, 20))
                .thenReturn(new PolicyPageData(List.of(policy("DRAFT", 0)), 41));

        mvc.perform(get("/internal/v1/policies")
                        .param("tenantId", TENANT.toString())
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.next.href").value(
                        "/internal/v1/policies?tenantId=" + TENANT + "&page=2&size=20"))
                .andExpect(jsonPath("$._links.prev.href").value(
                        "/internal/v1/policies?tenantId=" + TENANT + "&page=0&size=20"));
    }

    @Test
    void rejectsSizeAboveMaximum() throws Exception {
        mvc.perform(get("/internal/v1/policies")
                        .param("tenantId", TENANT.toString())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNegativePage() throws Exception {
        mvc.perform(get("/internal/v1/policies")
                        .param("tenantId", TENANT.toString())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void requiresTenantParameterAsProblem() throws Exception {
        mvc.perform(get("/internal/v1/policies"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"));

        verifyNoInteractions(repository);
    }

    @Test
    void returnsStructuredNotFoundProblem() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/policies/{id}", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/internal/v1/policies/" + POLICY_ID))
                .andExpect(jsonPath("$.errors[0].field").value("policyId"));
    }

    @Test
    void returnsEtagDerivedFromVersionOnGet() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 4)));

        mvc.perform(get("/internal/v1/policies/{id}", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void rejectsStaleIfMatchWithCanonicalConflict() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 2)));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"QUOTED","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.meta.currentVersion").value(2))
                .andExpect(jsonPath("$.meta.expectedVersion").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("If-Match"))
                .andExpect(jsonPath("$.errors[0].code").value("STALE_VERSION"));

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
    }

    @Test
    void ifMatchTakesPrecedenceOverExpectedVersion() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 1)));
        when(repository.updateStatus(TENANT, POLICY_ID, "QUOTED", "quote issued", 1))
                .thenReturn(policy("QUOTED", 2));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"QUOTED","expectedVersion":0,"reason":"quote issued"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.version").value(2));

        verify(repository).updateStatus(TENANT, POLICY_ID, "QUOTED", "quote issued", 1);
    }

    @Test
    void rejectsMalformedIfMatch() throws Exception {
        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "not-a-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"QUOTED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsIllegalStatusTransitionAsConflict() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ISSUED","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
    }

    @Test
    void createsDraftPolicyWithIdempotencyRecord() throws Exception {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1")).thenReturn(Optional.empty());
        when(repository.insertIdempotencyIfAbsent(eq(TENANT), eq("idem-1"), anyString(), any(UUID.class)))
                .thenReturn(true);
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(repository).insertIdempotencyIfAbsent(
                eq(TENANT), eq("idem-1"), eq(IdempotencyKeys.requestHash(request)), any(UUID.class));
        verify(repository).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void replaysIdempotentCreateWithOkStatus() throws Exception {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        POLICY_ID, IdempotencyKeys.requestHash(request))));
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()));

        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequest() throws Exception {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(POLICY_ID, "different-hash")));

        mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.meta.existingPolicyId").value(POLICY_ID.toString()));

        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void createsWithoutKeyWithoutStoringIdempotency() throws Exception {
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(repository, never()).insertIdempotencyIfAbsent(any(), any(), any(), any());
    }

    @Test
    void rejectsExpirationBeforeEffectiveDateWithFieldErrors() throws Exception {
        CreatePolicyRequest request = new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null,
                EFFECTIVE.plusDays(2), EFFECTIVE, BigDecimal.TEN, "USD", BigDecimal.TEN, "USD");

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_DATE_RANGE"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNegativePremium() throws Exception {
        CreatePolicyRequest request = new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("-1"), "USD", BigDecimal.TEN, "USD");

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("premiumAmount"))
                .andExpect(jsonPath("$.errors[0].code").value("MIN_VALUE"));

        verifyNoInteractions(repository);
    }

    @Test
    void unexpectedErrorIsInternalProblemWithoutLeakingDetails() throws Exception {
        when(repository.findPage(TENANT, 0, 20)).thenThrow(new IllegalStateException("SQL leaked"));

        mvc.perform(get("/internal/v1/policies").param("tenantId", TENANT.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.category").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value(
                        "The Policy expert could not complete the request."));
    }

    private static CreatePolicyRequest request() {
        return new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null, EFFECTIVE, EXPIRATION,
                new BigDecimal("125000.00"), "USD", new BigDecimal("2000000.00"), "USD");
    }

    private static Policy policy(String status, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, status,
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED);
    }
}
