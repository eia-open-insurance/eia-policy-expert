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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PolicyVersionController.class)
class PolicyVersionControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PolicyVersionService service;

    @Test
    void listsVersionHistoryWithThePageEnvelope() throws Exception {
        when(service.list(TENANT, POLICY_ID, 0, 20))
                .thenReturn(new ItemPage<>(List.of(version()), 0, 20, 1,
                        new ItemPage.Links(new ItemPage.Link("/internal/v1/policies/" + POLICY_ID
                                + "/versions?tenantId=" + TENANT + "&page=0&size=20"), null, null)));

        mvc.perform(get("/internal/v1/policies/{policyId}/versions", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].versionNumber").value(2))
                .andExpect(jsonPath("$.items[0].changeType").value("ENDORSEMENT"))
                .andExpect(jsonPath("$.items[0].immutableHash").value("hash"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$._links.self.href").exists());
    }

    @Test
    void returnsTheDerivedCurrentVersionWithHashVerification() throws Exception {
        when(service.current(TENANT, POLICY_ID))
                .thenReturn(new CurrentVersion(version(), true, true));

        mvc.perform(get("/internal/v1/policies/{policyId}/versions/current", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.hashVerified").value(true))
                .andExpect(jsonPath("$.version.status").value("ISSUED"));
    }

    @Test
    void returnsOneVersionSnapshot() throws Exception {
        when(service.get(TENANT, POLICY_ID, VERSION_ID)).thenReturn(version());

        mvc.perform(get("/internal/v1/policies/{policyId}/versions/{versionId}",
                        POLICY_ID, VERSION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(VERSION_ID.toString()))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-06-01"));
    }

    @Test
    void translatesAMissingVersionIntoACanonicalProblem() throws Exception {
        when(service.get(TENANT, POLICY_ID, VERSION_ID))
                .thenThrow(PolicyErrors.notFound("POLICY_VERSION_NOT_FOUND", "versionId",
                        "Policy version not found: " + VERSION_ID));

        mvc.perform(get("/internal/v1/policies/{policyId}/versions/{versionId}",
                        POLICY_ID, VERSION_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/internal/v1/policies/" + POLICY_ID
                        + "/versions/" + VERSION_ID));
    }

    @Test
    void rejectsAMissingTenantParameter() throws Exception {
        mvc.perform(get("/internal/v1/policies/{policyId}/versions", POLICY_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"));
    }

    @Test
    void rejectsInvalidPaginationAsAProblem() throws Exception {
        when(service.list(TENANT, POLICY_ID, 0, 101))
                .thenThrow(PolicyErrors.validation("Pagination parameters are invalid.",
                        List.of(new ApiException.FieldIssue("size", "OUT_OF_RANGE",
                                "size must be between 1 and 100"))));

        mvc.perform(get("/internal/v1/policies/{policyId}/versions", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "ENDORSEMENT", "ISSUED", LocalDate.of(2026, 6, 1), null, now, null, null, null,
                new BigDecimal("5000.0000"), "USD", "Increase the general aggregate", null,
                "hash", now);
    }
}
