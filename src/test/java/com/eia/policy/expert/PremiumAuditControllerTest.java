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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PremiumAuditController.class)
class PremiumAuditControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate AUDIT_DATE = LocalDate.of(2026, 6, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PremiumAuditService service;

    @Test
    void createsAnAuditOnTheExternalPathAndReturnsTheUpdatedPolicy() throws Exception {
        when(service.audit(eq(TENANT), eq(POLICY_ID), any(PremiumAuditRequest.class), eq("\"4\"")))
                .thenReturn(policy("IN_FORCE", "150000.0000", 5));

        mvc.perform(post("/api/v1/policies/{policyId}/premium-audit", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("150000.00"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.status").value("IN_FORCE"))
                .andExpect(jsonPath("$.premiumAmount").value("150000.0000"))
                .andExpect(jsonPath("$.version").value(5));
    }

    @Test
    void keepsTheInternalAlias() throws Exception {
        when(service.audit(eq(TENANT), eq(POLICY_ID), any(PremiumAuditRequest.class), eq(null)))
                .thenReturn(policy("IN_FORCE", "100000.0000", 5));

        mvc.perform(post("/internal/v1/policies/{policyId}/premium-audit", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("100000.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.premiumAmount").value("100000.0000"));
    }

    @Test
    void surfacesANoOpAuditAsA400Problem() throws Exception {
        when(service.audit(eq(TENANT), eq(POLICY_ID), any(PremiumAuditRequest.class), eq(null)))
                .thenThrow(PolicyErrors.badRequest("NO_PREMIUM_CHANGE", "auditedPremium",
                        "The audited premium must differ from the current policy premium."));

        mvc.perform(post("/api/v1/policies/{policyId}/premium-audit", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("120000.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NO_PREMIUM_CHANGE"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void rejectsANegativePremiumAtTheRequestBoundary() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/premium-audit", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("-1.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsABlankReasonAtTheRequestBoundary() throws Exception {
        PremiumAuditRequest request = new PremiumAuditRequest(new BigDecimal("150000.00"),
                AUDIT_DATE, "  ", null, null);

        mvc.perform(post("/api/v1/policies/{policyId}/premium-audit", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static PremiumAuditRequest request(String auditedPremium) {
        return new PremiumAuditRequest(new BigDecimal(auditedPremium), AUDIT_DATE,
                "Annual exposure audit", null, null);
    }

    private static Policy policy(String status, String premium, int version) {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal(premium), "USD", new BigDecimal("2000000.0000"), "USD",
                version, now, now);
    }
}
