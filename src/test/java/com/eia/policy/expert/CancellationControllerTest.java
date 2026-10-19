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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CancellationController.class)
class CancellationControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CANCELLATION_ID = UUID.fromString("eeeeeeee-eeee-7eee-8eee-eeeeeeeeeeee");
    private static final UUID REINSTATEMENT_ID = UUID.fromString("ffffffff-ffff-7fff-8fff-ffffffffffff");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CancellationService service;

    @Test
    void listsCancellations() throws Exception {
        when(service.listCancellations(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(cancellation()), 0, 20, 1, null));

        mvc.perform(get("/internal/v1/policies/{policyId}/cancellations", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cancellationType").value("PRO_RATA"))
                .andExpect(jsonPath("$.items[0].returnPremiumAmount").value("60164.4000"));
    }

    @Test
    void cancelsAPolicyAndReturnsTheNewEtag() throws Exception {
        when(service.cancel(eq(TENANT), eq(POLICY_ID), any(CancelPolicyRequest.class),
                eq("\"4\""), eq("key-1")))
                .thenReturn(new CancellationResult(cancellation(), cancelledPolicy(), false));

        mvc.perform(post("/internal/v1/policies/{policyId}/cancel", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"4\"")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-07-02"))
                .andExpect(jsonPath("$.proRataPct").value("0.501370"));
    }

    @Test
    void surfacesAnApprovalGateAsA428Problem() throws Exception {
        when(service.reinstate(eq(TENANT), eq(POLICY_ID), any(ReinstatePolicyRequest.class),
                eq(null), eq(null)))
                .thenThrow(PolicyErrors.approvalRequired("APPROVAL_REQUIRED", "approvalRef",
                        "Reinstatement requires an approval reference and approver."));

        mvc.perform(post("/internal/v1/policies/{policyId}/reinstate", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reinstateRequest())))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.category").value("APPROVAL_REQUIRED"));
    }

    @Test
    void reinstatesAPolicy() throws Exception {
        when(service.reinstate(eq(TENANT), eq(POLICY_ID), any(ReinstatePolicyRequest.class),
                eq("\"5\""), eq(null)))
                .thenReturn(new ReinstatementResult(reinstatement(), reinstatedPolicy(), false));

        mvc.perform(post("/internal/v1/policies/{policyId}/reinstate", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"5\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reinstateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.premiumEvidenceRef").value("RCPT-2026-0001"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void listsReinstatements() throws Exception {
        when(service.listReinstatements(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(reinstatement()), 0, 20, 1, null));

        mvc.perform(get("/internal/v1/policies/{policyId}/reinstatements", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].premiumEvidenceAmount").value("1500.0000"));
    }

    @Test
    void validatesTheCancellationBody() throws Exception {
        mvc.perform(post("/internal/v1/policies/{policyId}/cancel", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cancellationType":"PRO_RATA","reason":"","effectiveDate":"2026-07-02"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    private static CancelPolicyRequest cancelRequest() {
        return new CancelPolicyRequest("PRO_RATA", "Insured sold the business",
                LocalDate.of(2026, 7, 2), 0, null, null);
    }

    private static ReinstatePolicyRequest reinstateRequest() {
        return new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", APPROVER,
                APPROVAL, "RCPT-2026-0001", new BigDecimal("1500.00"), "USD");
    }

    private static PolicyCancellation cancellation() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new PolicyCancellation(CANCELLATION_ID, TENANT, POLICY_ID, UUID.randomUUID(),
                "PRO_RATA", "Insured sold the business", LocalDate.of(2026, 7, 2), 0, now, 365,
                183, new BigDecimal("0.501370"), new BigDecimal("59835.6000"),
                new BigDecimal("60164.4000"), "USD", null, null, "ACTIVE", now);
    }

    private static PolicyReinstatement reinstatement() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        return new PolicyReinstatement(REINSTATEMENT_ID, TENANT, POLICY_ID, CANCELLATION_ID,
                UUID.randomUUID(), LocalDate.of(2026, 8, 1), "Premium received", APPROVAL,
                APPROVER, "RCPT-2026-0001", new BigDecimal("1500.0000"), "USD", "APPROVED", now);
    }

    private static Policy cancelledPolicy() {
        return policy("CANCELLED", 5);
    }

    private static Policy reinstatedPolicy() {
        return policy("REINSTATED", 6);
    }

    private static Policy policy(String status, int version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("120000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                version, now, now);
    }
}
