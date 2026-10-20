package com.eia.policy.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingDunningController.class)
class BillingDunningControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PLAN_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID INSTALLMENT_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID ACTION_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final Instant SENT_AT = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BillingDunningService service;

    @Test
    void createsADunningActionAndReturnsCreated() throws Exception {
        when(service.create(eq(TENANT), eq(INSTALLMENT_ID),
                any(CreateDunningActionRequest.class))).thenReturn(action("FIRST_NOTICE", "EMAIL"));

        mvc.perform(post("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"first_notice","channel":"email","notes":"Lapse warning"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ACTION_ID.toString()))
                .andExpect(jsonPath("$.installmentId").value(INSTALLMENT_ID.toString()))
                .andExpect(jsonPath("$.planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.policyId").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.level").value("FIRST_NOTICE"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.notes").value("Lapse warning"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").value("2026-06-15T12:00:00Z"));
    }

    @Test
    void acceptsTheTenantQueryParameterAndTheInternalAlias() throws Exception {
        when(service.create(eq(TENANT), eq(INSTALLMENT_ID),
                any(CreateDunningActionRequest.class))).thenReturn(action("REMINDER", "PORTAL"));

        mvc.perform(post("/internal/v1/billing/installments/{installmentId}/dunning",
                        INSTALLMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"REMINDER","channel":"PORTAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.level").value("REMINDER"));
    }

    @Test
    void listsTheDunningHistoryNewestFirst() throws Exception {
        when(service.list(TENANT, INSTALLMENT_ID)).thenReturn(List.of(
                action("SECOND_NOTICE", "LETTER"), action("REMINDER", "EMAIL")));

        mvc.perform(get("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].level").value("SECOND_NOTICE"))
                .andExpect(jsonPath("$[1].level").value("REMINDER"));
    }

    @Test
    void returnsAnEmptyHistoryWhenNoActionExists() throws Exception {
        when(service.list(TENANT, INSTALLMENT_ID)).thenReturn(List.of());

        mvc.perform(get("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void requiresATenantWhenNeitherSourceIsPresent() throws Exception {
        mvc.perform(get("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));
    }

    @Test
    void rejectsAnotherBook() throws Exception {
        mvc.perform(get("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsNotesLongerThanTwoThousandCharactersBeforeCallingTheService()
            throws Exception {
        mvc.perform(post("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "level", "REMINDER",
                                "channel", "EMAIL",
                                "notes", "x".repeat(2001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("notes"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_LENGTH"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsBlankLevelAndChannel() throws Exception {
        mvc.perform(post("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"","channel":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void surfacesTheDuplicateAndNotDunnableConflicts() throws Exception {
        when(service.create(eq(TENANT), eq(INSTALLMENT_ID),
                any(CreateDunningActionRequest.class)))
                .thenThrow(PolicyErrors.conflict("DUNNING_ALREADY_SENT", "level",
                        "A REMINDER dunning action already exists"));

        mvc.perform(post("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"REMINDER","channel":"EMAIL"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUNNING_ALREADY_SENT"));

        when(service.create(eq(TENANT), eq(INSTALLMENT_ID),
                any(CreateDunningActionRequest.class)))
                .thenThrow(PolicyErrors.conflict("INSTALLMENT_NOT_DUNNABLE", "status",
                        "Installment is PAID and cannot be dunned."));

        mvc.perform(post("/api/v1/billing/installments/{installmentId}/dunning", INSTALLMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"REMINDER","channel":"EMAIL"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTALLMENT_NOT_DUNNABLE"));
    }

    private static BillingDunningAction action(String level, String channel) {
        return new BillingDunningAction(ACTION_ID, TENANT, INSTALLMENT_ID, PLAN_ID, POLICY_ID,
                level, channel, "Lapse warning", "SENT", SENT_AT, SENT_AT);
    }
}
