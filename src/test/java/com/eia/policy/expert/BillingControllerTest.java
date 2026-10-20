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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
class BillingControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PLAN_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID INSTALLMENT_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID PAYMENT_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);
    private static final Instant CREATED = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BillingService service;

    @Test
    void getsThePlanAndItsInstallments() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenReturn(view());

        mvc.perform(get("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.planNumber").value("BP-0001"))
                .andExpect(jsonPath("$.plan.totalPremium").value("120000.0000"))
                .andExpect(jsonPath("$.plan.installmentCount").value(3))
                .andExpect(jsonPath("$.plan.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[2].amount").value("40000.0000"))
                .andExpect(jsonPath("$.installments[2].status").value("DUE"));
    }

    @Test
    void createsAPlanAndReturnsCreated() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateBillingPlanRequest.class)))
                .thenReturn(view());

        mvc.perform(post("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(3, "MONTHLY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.currency").value("USD"))
                .andExpect(jsonPath("$.installments[0].installmentNumber").value(1));
    }

    @Test
    void keepsTheInternalAliasForTheDomainAdapter() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenReturn(view());

        mvc.perform(get("/internal/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.planNumber").value("BP-0001"));
    }

    @Test
    void returns404WhenThePolicyHasNoPlan() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenThrow(PolicyErrors.notFound(
                "BILLING_PLAN_NOT_FOUND", "policyId", "No billing plan exists for policy"));

        mvc.perform(get("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BILLING_PLAN_NOT_FOUND"));
    }

    @Test
    void returns409WhenAnActivePlanAlreadyExists() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateBillingPlanRequest.class)))
                .thenThrow(PolicyErrors.conflict("BILLING_PLAN_ALREADY_EXISTS", "policyId",
                        "An ACTIVE billing plan already exists"));

        mvc.perform(post("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(2, "ANNUAL"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_PLAN_ALREADY_EXISTS"));
    }

    @Test
    void rejectsInstallmentCountsOutsideOneToTwentyFour() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(0, "MONTHLY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("installmentCount"));

        mvc.perform(post("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(25, "MONTHLY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MAX_VALUE"));
    }

    @Test
    void rejectsABlankFrequency() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/billing", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(2, ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("frequency"));
    }

    @Test
    void marksAnInstallmentPaid() throws Exception {
        when(service.updateInstallment(eq(TENANT), eq(POLICY_ID), eq(INSTALLMENT_ID),
                any(UpdateInstallmentRequest.class))).thenReturn(paidInstallment());

        mvc.perform(patch("/api/v1/policies/{policyId}/billing/installments/{installmentId}",
                        POLICY_ID, INSTALLMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAID","paymentId":"%s"}
                                """.formatted(PAYMENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.amount").value("40000.0000"));
    }

    @Test
    void validatesTheInstallmentStatusBody() throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/billing/installments/{installmentId}",
                        POLICY_ID, INSTALLMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    private static CreateBillingPlanRequest request(int count, String frequency) {
        return new CreateBillingPlanRequest(count, frequency, FIRST_DUE);
    }

    private static BillingPlanView view() {
        PolicyBillingPlan plan = new PolicyBillingPlan(PLAN_ID, TENANT, POLICY_ID, "BP-0001",
                new BigDecimal("120000.0000"), "USD", 3, "MONTHLY", FIRST_DUE, "ACTIVE", CREATED);
        return new BillingPlanView(plan, List.of(
                installment(1, "40000.0000", "DUE", null),
                installment(2, "40000.0000", "DUE", null),
                installment(3, "40000.0000", "DUE", null)));
    }

    private static PolicyInstallment paidInstallment() {
        return installment(1, "40000.0000", "PAID", CREATED);
    }

    private static PolicyInstallment installment(int number, String amount, String status,
                                                 Instant paidAt) {
        return new PolicyInstallment(UUID.nameUUIDFromBytes(("installment-" + number).getBytes()),
                TENANT, PLAN_ID, number, FIRST_DUE.plusMonths(number - 1L),
                new BigDecimal(amount), status, paidAt,
                "PAID".equals(status) ? PAYMENT_ID : null, CREATED);
    }
}
