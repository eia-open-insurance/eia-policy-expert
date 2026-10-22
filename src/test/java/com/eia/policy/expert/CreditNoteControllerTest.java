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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreditNoteController.class)
class CreditNoteControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID NOTE_ID = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final UUID INSTALLMENT_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final Instant CREATED = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant REVERSED_AT = Instant.parse("2026-02-01T09:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreditNoteService service;

    @Test
    void listsCreditNotes() throws Exception {
        when(service.list(TENANT, POLICY_ID)).thenReturn(List.of(note("DRAFT", null, null)));

        mvc.perform(get("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$[0].noteNumber").value("CN-0000001"))
                .andExpect(jsonPath("$[0].amount").value("1500.5000"))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void createsADraftCreditNoteAndReturnsCreated() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateCreditNoteRequest.class)))
                .thenReturn(note("DRAFT", null, null));

        mvc.perform(post("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1500.50,"currency":"USD","reason":"adjustment"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.noteNumber").value("CN-0000001"))
                .andExpect(jsonPath("$.amount").value("1500.5000"));
    }

    @Test
    void acceptsTheCancellationIdAndReturnsTheLink() throws Exception {
        UUID cancellationId = UUID.fromString("88888888-8888-7888-8888-888888888888");
        when(service.create(eq(TENANT), eq(POLICY_ID),
                argThat(request -> cancellationId.equals(request.cancellationId()))))
                .thenReturn(linkedNote(cancellationId));

        mvc.perform(post("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1500.50,"currency":"USD","reason":"cancellation refund",
                                 "cancellationId":"%s"}
                                """.formatted(cancellationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancellationId").value(cancellationId.toString()))
                .andExpect(jsonPath("$.cancellationNumber").value("CX-0000001"));
    }

    @Test
    void rejectsANonPositiveAmount() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("MIN_VALUE"));
    }

    @Test
    void rejectsABlankCurrency() throws Exception {
        mvc.perform(post("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.00,"currency":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currency"));
    }

    @Test
    void rejectsAReasonLongerThanFiveHundredCharacters() throws Exception {
        String reason = "x".repeat(501);

        mvc.perform(post("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCreditNoteRequest(BigDecimal.TEN, "USD", reason))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_LENGTH"));
    }

    @Test
    void transitionsTheStatus() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(POLICY_ID), eq(NOTE_ID),
                any(UpdateCreditNoteStatusRequest.class)))
                .thenReturn(note("ISSUED", CREATED, null));

        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ISSUED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issuedAt").value(CREATED.toString()));
    }

    @Test
    void acceptsTheInstallmentIdWhenApplying() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(POLICY_ID), eq(NOTE_ID),
                any(UpdateCreditNoteStatusRequest.class)))
                .thenReturn(note("APPLIED", CREATED, CREATED, INSTALLMENT_ID));

        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPLIED","installmentId":"%s"}
                                """.formatted(INSTALLMENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.appliedInstallmentId").value(INSTALLMENT_ID.toString()));
    }

    @Test
    void reversesAnAppliedCreditNoteWithAReason() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(POLICY_ID), eq(NOTE_ID),
                argThat(request -> "REVERSED".equals(request.status())
                        && "billing correction".equals(request.reason()))))
                .thenReturn(reversedNote());

        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REVERSED","reason":"billing correction"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversedAt").value(REVERSED_AT.toString()))
                .andExpect(jsonPath("$.reversalReason").value("billing correction"))
                .andExpect(jsonPath("$.appliedInstallmentId")
                        .value(INSTALLMENT_ID.toString()));
    }

    @Test
    void rejectsAReversalReasonLongerThanFiveHundredCharacters() throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateCreditNoteStatusRequest("REVERSED", null,
                                        "x".repeat(501)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void validatesTheStatusBody() throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void returns409ForAnInvalidTransition() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(POLICY_ID), eq(NOTE_ID),
                any(UpdateCreditNoteStatusRequest.class)))
                .thenThrow(PolicyErrors.conflict("INVALID_CREDIT_NOTE_TRANSITION", "status",
                        "Credit note is terminal"));

        mvc.perform(patch("/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status",
                        POLICY_ID, NOTE_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CANCELLED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDIT_NOTE_TRANSITION"));
    }

    @Test
    void acceptsTheTenantHeader() throws Exception {
        when(service.list(TENANT, POLICY_ID)).thenReturn(List.of(note("DRAFT", null, null)));

        mvc.perform(get("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noteNumber").value("CN-0000001"));
    }

    @Test
    void requiresATenant() throws Exception {
        mvc.perform(get("/api/v1/policies/{policyId}/credit-notes", POLICY_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));
    }

    @Test
    void rejectsANonDirectBook() throws Exception {
        mvc.perform(get("/api/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void keepsTheInternalAliasForTheDomainAdapter() throws Exception {
        when(service.list(TENANT, POLICY_ID)).thenReturn(List.of(note("DRAFT", null, null)));

        mvc.perform(get("/internal/v1/policies/{policyId}/credit-notes", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    private static PolicyCreditNote note(String status, Instant issuedAt, Instant appliedAt) {
        return note(status, issuedAt, appliedAt, null);
    }

    private static PolicyCreditNote note(String status, Instant issuedAt, Instant appliedAt,
                                         UUID appliedInstallmentId) {
        return new PolicyCreditNote(NOTE_ID, TENANT, POLICY_ID, "CN-0000001",
                new BigDecimal("1500.5000"), "USD", "adjustment", status, issuedAt, appliedAt,
                appliedInstallmentId, CREATED);
    }

    private static PolicyCreditNote linkedNote(UUID cancellationId) {
        return new PolicyCreditNote(NOTE_ID, TENANT, POLICY_ID, "CN-0000001",
                new BigDecimal("1500.5000"), "USD", "cancellation refund", "DRAFT", null, null,
                null, CREATED, null, null, cancellationId, "CX-0000001");
    }

    private static PolicyCreditNote reversedNote() {
        return new PolicyCreditNote(NOTE_ID, TENANT, POLICY_ID, "CN-0000001",
                new BigDecimal("1500.5000"), "USD", "adjustment", "REVERSED", CREATED, CREATED,
                INSTALLMENT_ID, CREATED, REVERSED_AT, "billing correction");
    }
}
