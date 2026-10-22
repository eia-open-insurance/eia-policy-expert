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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(UnderwritingSubmissionController.class)
class UnderwritingSubmissionControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID POLICY_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);
    private static final Instant CREATED = Instant.parse("2026-10-22T12:00:00Z");
    private static final String CREATE_BODY = """
            {"applicantPartyId":"22222222-2222-7222-8222-222222222222",
             "productCode":"CGL_PRODUCTS","requestedEffectiveDate":"2026-11-01",
             "requestedLimitAmount":2500000.00,"requestedLimitCurrency":"USD",
             "tortCodes":["ASBESTOS","PFAS"],"notes":"Large manufacturing risk"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UnderwritingSubmissionService service;

    @MockitoBean
    private SubmissionConversionService conversionService;

    @Test
    void createsASubmissionUnderThePublicPath() throws Exception {
        when(service.create(eq(TENANT), any(CreateUnderwritingSubmissionRequest.class)))
                .thenReturn(view("RECEIVED", 0));

        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$.submissionNumber").value("SUB-2026-0000001"))
                .andExpect(jsonPath("$.applicantPartyId").value(APPLICANT.toString()))
                .andExpect(jsonPath("$.productCode").value("CGL_PRODUCTS"))
                .andExpect(jsonPath("$.requestedEffectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.requestedLimitAmount").isNumber())
                .andExpect(jsonPath("$.requestedLimitCurrency").value("USD"))
                .andExpect(jsonPath("$.tortCodes", hasSize(2)))
                .andExpect(jsonPath("$.tortCodes[0]").value("ASBESTOS"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.notes").value("Large manufacturing risk"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-22T12:00:00Z"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        ArgumentCaptor<CreateUnderwritingSubmissionRequest> captor =
                ArgumentCaptor.forClass(CreateUnderwritingSubmissionRequest.class);
        verify(service).create(eq(TENANT), captor.capture());
        assertThat(captor.getValue().applicantPartyId()).isEqualTo(APPLICANT);
        assertThat(captor.getValue().tortCodes()).containsExactly("ASBESTOS", "PFAS");
        assertThat(captor.getValue().notes()).isEqualTo("Large manufacturing risk");
    }

    @Test
    void acceptsTheTenantHeaderAndTheInternalAlias() throws Exception {
        when(service.create(eq(TENANT), any(CreateUnderwritingSubmissionRequest.class)))
                .thenReturn(view("RECEIVED", 0));

        mvc.perform(post("/internal/v1/policies/submissions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()));

        verify(service).create(eq(TENANT), any(CreateUnderwritingSubmissionRequest.class));
    }

    @Test
    void rejectsAMissingTenant() throws Exception {
        mvc.perform(post("/api/v1/policies/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonDirectBook() throws Exception {
        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsAnInvalidCreateBodyBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("applicantPartyId"));

        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicantPartyId":"22222222-2222-7222-8222-222222222222",
                                 "productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":-1,"requestedLimitCurrency":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field",
                        org.hamcrest.Matchers.hasItems("requestedLimitAmount",
                                "requestedLimitCurrency")));

        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicantPartyId":"22222222-2222-7222-8222-222222222222",
                                 "productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD",
                                 "tortCodes":["A","B","C","D","E","F","G","H","I","J","K","L",
                                 "M","N","O","P","Q","R","S","T","U"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("tortCodes"));

        verifyNoInteractions(service);
    }

    @Test
    void listsSubmissionsWithTheQueryAndStatusFilters() throws Exception {
        when(service.list(TENANT, "acme", "RECEIVED"))
                .thenReturn(List.of(view("RECEIVED", 0)));

        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("q", "acme")
                        .param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$[0].submissionNumber").value("SUB-2026-0000001"))
                .andExpect(jsonPath("$[0].status").value("RECEIVED"));

        verify(service).list(TENANT, "acme", "RECEIVED");
    }

    @Test
    void listsSubmissionsUnderTheInternalAlias() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of());

        mvc.perform(get("/internal/v1/policies/submissions")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(service).list(TENANT, null, null);
    }

    @Test
    void getsOneSubmission() throws Exception {
        when(service.get(TENANT, SUBMISSION_ID)).thenReturn(view("REFERRED", 1));

        mvc.perform(get("/api/v1/policies/submissions/{id}", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void returnsStructuredNotFoundForAnUnknownSubmission() throws Exception {
        when(service.get(TENANT, SUBMISSION_ID)).thenThrow(PolicyErrors.notFound(
                "SUBMISSION_NOT_FOUND", "submissionId",
                "Underwriting submission not found: " + SUBMISSION_ID));

        mvc.perform(get("/api/v1/policies/submissions/{id}", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("submissionId"));
    }

    @Test
    void transitionsTheStatusAndReturnsTheNewEtag() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(SUBMISSION_ID),
                any(UpdateUnderwritingSubmissionStatusRequest.class)))
                .thenReturn(view("REFERRED", 1));

        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFERRED\",\"reason\":\"Large limit\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.version").value(1));

        ArgumentCaptor<UpdateUnderwritingSubmissionStatusRequest> captor =
                ArgumentCaptor.forClass(UpdateUnderwritingSubmissionStatusRequest.class);
        verify(service).updateStatus(eq(TENANT), eq(SUBMISSION_ID), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("REFERRED");
        assertThat(captor.getValue().reason()).isEqualTo("Large limit");
    }

    @Test
    void rejectsABlankTargetStatus() throws Exception {
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));

        verifyNoInteractions(service);
    }

    @Test
    void mapsAnInvalidTransitionToAConflict() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(SUBMISSION_ID),
                any(UpdateUnderwritingSubmissionStatusRequest.class)))
                .thenThrow(PolicyErrors.conflict("INVALID_SUBMISSION_TRANSITION", "status",
                        "Submission cannot transition."));

        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOUND\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_TRANSITION"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void convertsASubmissionAndReturnsTheCreatedDraftPolicy() throws Exception {
        when(conversionService.convert(eq(TENANT), eq(SUBMISSION_ID),
                any(ConvertSubmissionRequest.class)))
                .thenReturn(new SubmissionConversionResult(policy(1), view("BOUND", 3), false));

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-0000001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$.premiumAmount").value("125000.0000"));

        ArgumentCaptor<ConvertSubmissionRequest> captor =
                ArgumentCaptor.forClass(ConvertSubmissionRequest.class);
        verify(conversionService).convert(eq(TENANT), eq(SUBMISSION_ID), captor.capture());
        assertThat(captor.getValue().premiumAmount()).isEqualByComparingTo("125000");
        assertThat(captor.getValue().premiumCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().expirationDate()).isNull();
    }

    @Test
    void replaysAConversionAsOkWithTheExistingPolicy() throws Exception {
        when(conversionService.convert(eq(TENANT), eq(SUBMISSION_ID),
                any(ConvertSubmissionRequest.class)))
                .thenReturn(new SubmissionConversionResult(policy(1), view("BOUND", 3), true));

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()));
    }

    @Test
    void rejectsAConversionWithoutAPremiumBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("premiumAmount"));

        verifyNoInteractions(conversionService);
    }

    @Test
    void mapsANonConvertibleSubmissionToAConflict() throws Exception {
        when(conversionService.convert(eq(TENANT), eq(SUBMISSION_ID),
                any(ConvertSubmissionRequest.class)))
                .thenThrow(PolicyErrors.conflict("SUBMISSION_NOT_CONVERTIBLE", "status",
                        "Submission is RECEIVED.", java.util.Map.of(
                                "currentStatus", "RECEIVED")));

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", SUBMISSION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_CONVERTIBLE"))
                .andExpect(jsonPath("$.meta.currentStatus").value("RECEIVED"));
    }

    private static UnderwritingSubmissionView view(String status, int version) {
        return new UnderwritingSubmissionView(SUBMISSION_ID, "SUB-2026-0000001", APPLICANT,
                "CGL_PRODUCTS", EFFECTIVE, new BigDecimal("2500000.0000"), "USD",
                List.of("ASBESTOS", "PFAS"), status, POLICY_ID, "Large manufacturing risk",
                version, CREATED, CREATED);
    }

    private static Policy policy(int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL_PRODUCTS", APPLICANT,
                "DRAFT", EFFECTIVE, EFFECTIVE.plusYears(1), new BigDecimal("125000.0000"),
                "USD", new BigDecimal("2500000.0000"), "USD", version, CREATED, CREATED,
                null, null, null, SUBMISSION_ID);
    }
}
