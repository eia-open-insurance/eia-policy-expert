package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PolicyRenewalController.class)
class PolicyRenewalControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SOURCE_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RENEWAL_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate EFFECTIVE = LocalDate.of(2027, 1, 2);
    private static final LocalDate EXPIRATION = LocalDate.of(2028, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PolicyRenewalService service;

    @Test
    void renewsUnderTheApiPrefixAndReturnsTheNewDraftPolicy() throws Exception {
        when(service.renew(eq(TENANT), eq(SOURCE_ID), any(RenewPolicyRequest.class)))
                .thenReturn(renewedPolicy());

        mvc.perform(post("/api/v1/policies/{policyId}/renew", SOURCE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expirationDate":"2028-01-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(RENEWAL_ID.toString()))
                .andExpect(jsonPath("$.policyNumber").value("POL-2027-0000002"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.renewedFromPolicyId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.effectiveDate").value("2027-01-02"))
                .andExpect(jsonPath("$.expirationDate").value("2028-01-01"))
                .andExpect(jsonPath("$.premiumAmount").value("125000.0000"))
                .andExpect(jsonPath("$.limitAmount").value("2000000.0000"));

        ArgumentCaptor<RenewPolicyRequest> request =
                ArgumentCaptor.forClass(RenewPolicyRequest.class);
        verify(service).renew(eq(TENANT), eq(SOURCE_ID), request.capture());
        assertThat(request.getValue().effectiveDate()).isNull();
        assertThat(request.getValue().premiumAmount()).isNull();
        assertThat(request.getValue().premiumCurrency()).isNull();
        assertThat(request.getValue().limitAmount()).isNull();
        assertThat(request.getValue().limitCurrency()).isNull();
    }

    @Test
    void keepsTheInternalV1AliasForTheDomainAdapter() throws Exception {
        when(service.renew(eq(TENANT), eq(SOURCE_ID), any(RenewPolicyRequest.class)))
                .thenReturn(renewedPolicy());

        mvc.perform(post("/internal/v1/policies/{policyId}/renew", SOURCE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expirationDate":"2028-01-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renewedFromPolicyId").value(SOURCE_ID.toString()));
    }

    @Test
    void surfacesRenewalNotAllowedAsAConflictProblem() throws Exception {
        when(service.renew(eq(TENANT), eq(SOURCE_ID), any(RenewPolicyRequest.class)))
                .thenThrow(PolicyErrors.conflict("RENEWAL_NOT_ALLOWED", "status",
                        "Policy POL-2026-0000001 in status CANCELLED cannot be renewed."));

        mvc.perform(post("/api/v1/policies/{policyId}/renew", SOURCE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expirationDate":"2028-01-01"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RENEWAL_NOT_ALLOWED"))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void requiresExpirationDateBeforeCallingTheService() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/renew", SOURCE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("expirationDate"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsAMalformedOptionalCurrency() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/renew", SOURCE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expirationDate":"2028-01-01","premiumCurrency":"usd"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("premiumCurrency"));

        verifyNoInteractions(service);
    }

    @Test
    void requiresTheTenantWallParameter() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/renew", SOURCE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expirationDate":"2028-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"));

        verifyNoInteractions(service);
    }

    private static Policy renewedPolicy() {
        return new Policy(RENEWAL_ID, TENANT, "POL-2027-0000002", "CGL", PARTY, "DRAFT",
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", 0, CREATED, CREATED, SOURCE_ID);
    }
}
