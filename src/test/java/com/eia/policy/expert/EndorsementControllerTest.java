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

@WebMvcTest(EndorsementController.class)
class EndorsementControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID ENDORSEMENT_ID = UUID.fromString("bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EndorsementService service;

    @Test
    void listsEndorsements() throws Exception {
        when(service.list(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(endorsement()), 0, 20, 1,
                        new ItemPage.Links(new ItemPage.Link("/internal/v1/policies/" + POLICY_ID
                                + "/endorsements?tenantId=" + TENANT + "&page=0&size=20"), null, null)));

        mvc.perform(get("/internal/v1/policies/{policyId}/endorsements", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].endorsementNumber").value("END-0001"))
                .andExpect(jsonPath("$.items[0].coverageImpact").value("BROADEN"))
                .andExpect(jsonPath("$.items[0].premiumDeltaAmount").value("5000.0000"));
    }

    @Test
    void createsAnEndorsementWithVersionMetadataHeaders() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateEndorsementRequest.class),
                eq("\"4\""), eq("key-1")))
                .thenReturn(new EndorsementResult(endorsement(), version(), 5, false));

        mvc.perform(post("/internal/v1/policies/{policyId}/endorsements", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"4\"")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.endorsementType").value("LIMIT"))
                .andExpect(jsonPath("$.policyVersionId").value(VERSION_ID.toString()));
    }

    @Test
    void surfacesAMissingApprovalAsA428Problem() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateEndorsementRequest.class),
                eq(null), eq(null)))
                .thenThrow(PolicyErrors.approvalRequired("APPROVAL_REQUIRED", "approvalRef",
                        "An endorsement backdated before policy inception requires approval."));

        mvc.perform(post("/internal/v1/policies/{policyId}/endorsements", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.category").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void surfacesABusinessRuleAsA422Problem() throws Exception {
        when(service.create(eq(TENANT), eq(POLICY_ID), any(CreateEndorsementRequest.class),
                eq(null), eq(null)))
                .thenThrow(PolicyErrors.businessRule("VERSION_REGRESSION", "effectiveDate",
                        "The change would regress the version timeline."));

        mvc.perform(post("/internal/v1/policies/{policyId}/endorsements", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_REGRESSION"))
                .andExpect(jsonPath("$.category").value("BUSINESS_RULE"));
    }

    @Test
    void returnsOneEndorsement() throws Exception {
        when(service.get(TENANT, POLICY_ID, ENDORSEMENT_ID)).thenReturn(endorsement());

        mvc.perform(get("/internal/v1/policies/{policyId}/endorsements/{endorsementId}",
                        POLICY_ID, ENDORSEMENT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ENDORSEMENT_ID.toString()));
    }

    private static CreateEndorsementRequest request() {
        return new CreateEndorsementRequest(null, "LIMIT", LocalDate.of(2026, 6, 1),
                "Increase the general aggregate", new BigDecimal("5000.00"), "USD", "BROADEN",
                List.of(new CreateEndorsementRequest.CoverageChange("GL_PRODUCTS", "BROADEN",
                        "Aggregate increased")), null, null, null, null, null);
    }

    private static PolicyEndorsement endorsement() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyEndorsement(ENDORSEMENT_ID, TENANT, POLICY_ID, VERSION_ID, "END-0001", 1,
                null, null, "LIMIT", LocalDate.of(2026, 6, 1), "Increase the general aggregate",
                new BigDecimal("5000.0000"), "USD", "BROADEN",
                List.of(new EndorsementCoverageChange("GL_PRODUCTS", "BROADEN",
                        "Aggregate increased")), false, null, null, null, "ISSUED", now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "ENDORSEMENT", "ISSUED", LocalDate.of(2026, 6, 1), null, now, null, null, null,
                new BigDecimal("5000.0000"), "USD", "Increase the general aggregate", null,
                "hash", now);
    }
}
