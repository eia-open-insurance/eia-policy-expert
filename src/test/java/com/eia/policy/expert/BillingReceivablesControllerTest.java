package com.eia.policy.expert;

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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingReceivablesController.class)
class BillingReceivablesControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PLAN_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID INSTALLMENT_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID PARTY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate DUE = LocalDate.of(2026, 6, 1);
    private static final LocalDate DUE_BEFORE = LocalDate.of(2026, 6, 30);
    private static final Instant DUNNING_AT = Instant.parse("2026-06-10T09:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BillingService service;

    @Test
    void listsReceivablesUsingTheTenantHeader() throws Exception {
        when(service.listReceivables(eq(TENANT), isNull(), isNull(), eq(100)))
                .thenReturn(view());

        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].installmentId").value(INSTALLMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.items[0].planNumber").value("BP-0001"))
                .andExpect(jsonPath("$.items[0].policyId").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.items[0].policyNumber").value("POL-0001"))
                .andExpect(jsonPath("$.items[0].insuredPartyId").value(PARTY_ID.toString()))
                .andExpect(jsonPath("$.items[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.items[0].dueDate").value("2026-06-01"))
                .andExpect(jsonPath("$.items[0].amount").value("1000.0000"))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.items[0].daysPastDue").value(14))
                .andExpect(jsonPath("$.items[0].lastDunningLevel").value("FIRST_NOTICE"))
                .andExpect(jsonPath("$.items[0].lastDunningAt").value("2026-06-10T09:00:00Z"))
                .andExpect(jsonPath("$.totalAmount").value("1000.0000"))
                .andExpect(jsonPath("$.overdueAmount").value("1000.0000"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void acceptsTheTenantQueryParameterAndPassesTheFilters() throws Exception {
        when(service.listReceivables(eq(TENANT), eq("OVERDUE"), eq(DUE_BEFORE), eq(25)))
                .thenReturn(view());

        mvc.perform(get("/api/v1/billing/installments")
                        .param("tenantId", TENANT.toString())
                        .param("status", "OVERDUE")
                        .param("dueBefore", "2026-06-30")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        verify(service).listReceivables(TENANT, "OVERDUE", DUE_BEFORE, 25);
    }

    @Test
    void defaultsTheLimitToOneHundredAndTheFiltersToAbsent() throws Exception {
        when(service.listReceivables(eq(TENANT), isNull(), isNull(), eq(100)))
                .thenReturn(view());

        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk());

        verify(service).listReceivables(TENANT, null, null, 100);
    }

    @Test
    void keepsTheInternalAliasForTheDomainAdapter() throws Exception {
        when(service.listReceivables(eq(TENANT), isNull(), isNull(), eq(100)))
                .thenReturn(view());

        mvc.perform(get("/internal/v1/billing/installments")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].policyNumber").value("POL-0001"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void rejectsAnotherBook() throws Exception {
        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void requiresATenantWhenNeitherSourceIsPresent() throws Exception {
        mvc.perform(get("/api/v1/billing/installments"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));
    }

    @Test
    void rejectsAMalformedTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_HEADER"));
    }

    @Test
    void rejectsAnInvalidDueDate() throws Exception {
        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("dueBefore", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void surfacesTheInvalidStatusProblemFromTheBillingRules() throws Exception {
        when(service.listReceivables(eq(TENANT), eq("PAID"), isNull(), eq(100)))
                .thenThrow(PolicyErrors.badRequest("INVALID_INSTALLMENT_STATUS", "status",
                        "status must be DUE or OVERDUE."));

        mvc.perform(get("/api/v1/billing/installments")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("status", "PAID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INSTALLMENT_STATUS"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    private static ReceivablesView view() {
        InstallmentReceivable item = new InstallmentReceivable(INSTALLMENT_ID, PLAN_ID, "BP-0001",
                POLICY_ID, "POL-0001", PARTY_ID, 1, DUE, new BigDecimal("1000.0000"), "USD",
                "OVERDUE", 14, null, null, "FIRST_NOTICE", DUNNING_AT);
        return new ReceivablesView(List.of(item), new BigDecimal("1000.0000"),
                new BigDecimal("1000.0000"), 1);
    }
}
