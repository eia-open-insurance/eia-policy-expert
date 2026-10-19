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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void listsPoliciesForTenant() throws Exception {
        when(repository.findAll(TENANT)).thenReturn(List.of(policy("DRAFT", 0)));

        mvc.perform(get("/internal/v1/policies").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].policyNumber").value("POL-2026-0000001"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].premiumAmount").value("125000.0000"));
    }

    @Test
    void returnsStructuredNotFound() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/policies/{id}", POLICY_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/internal/v1/policies/" + POLICY_ID));
    }

    @Test
    void rejectsExpirationBeforeEffectiveDate() throws Exception {
        CreatePolicyRequest request = request(EFFECTIVE.plusDays(2), EFFECTIVE, BigDecimal.TEN, BigDecimal.TEN);

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("expirationDate"));

        verify(repository, never()).create(any());
    }

    @Test
    void rejectsNegativePremium() throws Exception {
        CreatePolicyRequest request = request(EFFECTIVE, EXPIRATION, new BigDecimal("-1"), BigDecimal.TEN);

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("premiumAmount"));

        verify(repository, never()).create(any());
    }

    @Test
    void createsDraftPolicy() throws Exception {
        when(repository.create(any(CreatePolicyRequest.class))).thenReturn(policy("DRAFT", 0));

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(EFFECTIVE, EXPIRATION,
                                new BigDecimal("125000.00"), new BigDecimal("2000000.00")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(repository).create(any(CreatePolicyRequest.class));
    }

    @Test
    void rejectsIllegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ISSUED","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
    }

    @Test
    void advancesStatusAndVersion() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));
        when(repository.updateStatus(eq(TENANT), eq(POLICY_ID), eq("QUOTED"), eq("quote issued"), eq(0)))
                .thenReturn(policy("QUOTED", 1));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"QUOTED","expectedVersion":0,"reason":"quote issued"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsStaleExpectedVersion() throws Exception {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 2)));

        mvc.perform(patch("/internal/v1/policies/{id}/status", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"QUOTED","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    private static CreatePolicyRequest request(LocalDate effective, LocalDate expiration,
                                              BigDecimal premium, BigDecimal limit) {
        return new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null, effective, expiration,
                premium, "USD", limit, "USD");
    }

    private static Policy policy(String status, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, status,
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED);
    }
}
