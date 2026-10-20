package com.eia.policy.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MassTortProfileController.class)
class MassTortProfileControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PROFILE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate BLOCK_START = LocalDate.of(1978, 1, 1);
    private static final LocalDate BLOCK_END = LocalDate.of(1985, 12, 31);
    private static final Instant UPDATED = Instant.parse("2026-10-20T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MassTortProfileService service;

    @Test
    void getsTheProfileUsingTheTenantParameter() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenReturn(profile());

        mvc.perform(get("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.policyId").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.tortType").value("ASBESTOS"))
                .andExpect(jsonPath("$.triggerType").value("EXPOSURE"))
                .andExpect(jsonPath("$.allocationMethod").value("ALL_SUMS"))
                .andExpect(jsonPath("$.allocationNotes").value("Allocate by policy year"))
                .andExpect(jsonPath("$.coverageBlockStart").value("1978-01-01"))
                .andExpect(jsonPath("$.coverageBlockEnd").value("1985-12-31"))
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    @Test
    void getsTheProfileUsingTheTenantHeaderAndDirectBook() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenReturn(profile());

        mvc.perform(get("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tortType").value("ASBESTOS"));
    }

    @Test
    void keepsTheInternalAliasForTheDomainAdapter() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenReturn(profile());

        mvc.perform(get("/internal/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tortType").value("ASBESTOS"));
    }

    @Test
    void rejectsANonDirectBookAsNotFound() throws Exception {
        mvc.perform(get("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(service);
    }

    @Test
    void returns404WhenThePolicyHasNoProfile() throws Exception {
        when(service.get(TENANT, POLICY_ID)).thenThrow(PolicyErrors.notFound(
                "MASS_TORT_PROFILE_NOT_FOUND", "policyId", "No mass tort profile exists"));

        mvc.perform(get("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MASS_TORT_PROFILE_NOT_FOUND"));
    }

    @Test
    void createsAProfileWith201() throws Exception {
        when(service.upsert(eq(TENANT), eq(POLICY_ID), any(PutMassTortProfileRequest.class)))
                .thenReturn(new MassTortProfileResult(profile(), true));

        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.tortType").value("ASBESTOS"));
    }

    @Test
    void updatesAnExistingProfileWith200() throws Exception {
        when(service.upsert(eq(TENANT), eq(POLICY_ID), any(PutMassTortProfileRequest.class)))
                .thenReturn(new MassTortProfileResult(profile(), false));

        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.coverageBlockStart").value("1978-01-01"));
    }

    @Test
    void rejectsABlankClassificationAndAnOversizedNote() throws Exception {
        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PutMassTortProfileRequest(
                                "", "EXPOSURE", "ALL_SUMS", null, null, null, "DRAFT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("tortType"));

        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PutMassTortProfileRequest(
                                "ASBESTOS", "EXPOSURE", "ALL_SUMS", "n".repeat(2001),
                                null, null, "DRAFT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("allocationNotes"));
    }

    @Test
    void surfacesServiceValidationCodes() throws Exception {
        when(service.upsert(eq(TENANT), eq(POLICY_ID), any(PutMassTortProfileRequest.class)))
                .thenThrow(PolicyErrors.badRequest("INVALID_TORT_TYPE", "tortType",
                        "tortType must be one of the known torts."));

        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PutMassTortProfileRequest(
                                "MOLD", "EXPOSURE", "ALL_SUMS", null, null, null, "DRAFT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TORT_TYPE"))
                .andExpect(jsonPath("$.errors[0].field").value("tortType"));
    }

    @Test
    void requiresATenantOnBothReadsAndWrites() throws Exception {
        mvc.perform(get("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        mvc.perform(put("/api/v1/policies/{policyId}/mass-tort-profile", POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));
        verifyNoInteractions(service);
    }

    private static PutMassTortProfileRequest completeRequest() {
        return new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS",
                "Allocate by policy year", BLOCK_START, BLOCK_END, "COMPLETE");
    }

    private static PolicyMassTortProfile profile() {
        return new PolicyMassTortProfile(PROFILE_ID, TENANT, POLICY_ID, "ASBESTOS", "EXPOSURE",
                "ALL_SUMS", "Allocate by policy year", BLOCK_START, BLOCK_END, "COMPLETE",
                UPDATED);
    }
}
