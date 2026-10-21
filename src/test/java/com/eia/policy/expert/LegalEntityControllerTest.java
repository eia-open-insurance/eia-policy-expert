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
import static org.hamcrest.Matchers.nullValue;
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

@WebMvcTest(LegalEntityController.class)
class LegalEntityControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LEGAL_ENTITY_ID =
            UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-21T12:00:00Z");
    private static final String CREATE_BODY = """
            {"code":"LE-001","name":"Acme Insurance","entityType":"INSURER",
             "jurisdiction":"US","regulator":"NAIC","licenseNumber":"LIC-42"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LegalEntityService service;

    @Test
    void createsALegalEntityUnderTheApiPrefix() throws Exception {
        when(service.create(eq(TENANT), any(CreateLegalEntityRequest.class)))
                .thenReturn(view());

        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalEntityId").value(LEGAL_ENTITY_ID.toString()))
                .andExpect(jsonPath("$.code").value("LE-001"))
                .andExpect(jsonPath("$.name").value("Acme Insurance"))
                .andExpect(jsonPath("$.entityType").value("INSURER"))
                .andExpect(jsonPath("$.jurisdiction").value("US"))
                .andExpect(jsonPath("$.regulator").value("NAIC"))
                .andExpect(jsonPath("$.licenseNumber").value("LIC-42"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:00:00Z"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        ArgumentCaptor<CreateLegalEntityRequest> captor =
                ArgumentCaptor.forClass(CreateLegalEntityRequest.class);
        verify(service).create(eq(TENANT), captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("LE-001");
        assertThat(captor.getValue().entityType()).isEqualTo("INSURER");
    }

    @Test
    void acceptsTheTenantHeaderAndOptionalFields() throws Exception {
        when(service.create(eq(TENANT), any(CreateLegalEntityRequest.class)))
                .thenReturn(view());

        mvc.perform(post("/api/v1/legal-entities")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LE-002","name":"Pool Co","entityType":"POOL",
                                 "jurisdiction":"GB"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalEntityId").value(LEGAL_ENTITY_ID.toString()));

        verify(service).create(eq(TENANT), any(CreateLegalEntityRequest.class));
    }

    @Test
    void rejectsAMissingTenant() throws Exception {
        mvc.perform(post("/api/v1/legal-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonDirectBook() throws Exception {
        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsABlankCode() throws Exception {
        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":" ","name":"Acme","entityType":"INSURER",
                                 "jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("code"));

        verifyNoInteractions(service);
    }

    @Test
    void listsLegalEntities() throws Exception {
        when(service.list(TENANT)).thenReturn(List.of(view()));

        mvc.perform(get("/api/v1/legal-entities").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].legalEntityId").value(LEGAL_ENTITY_ID.toString()))
                .andExpect(jsonPath("$[0].code").value("LE-001"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void listsLegalEntitiesUnderTheInternalAlias() throws Exception {
        when(service.list(TENANT)).thenReturn(List.of());

        mvc.perform(get("/internal/v1/legal-entities").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getsOneLegalEntity() throws Exception {
        when(service.get(TENANT, LEGAL_ENTITY_ID)).thenReturn(view());

        mvc.perform(get("/api/v1/legal-entities/{id}", LEGAL_ENTITY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntityId").value(LEGAL_ENTITY_ID.toString()))
                .andExpect(jsonPath("$.regulator").value("NAIC"));
    }

    @Test
    void returnsStructuredNotFoundForAnUnknownLegalEntity() throws Exception {
        when(service.get(TENANT, LEGAL_ENTITY_ID)).thenThrow(PolicyErrors.notFound(
                "LEGAL_ENTITY_NOT_FOUND", "legalEntityId",
                "Legal entity not found: " + LEGAL_ENTITY_ID));

        mvc.perform(get("/api/v1/legal-entities/{id}", LEGAL_ENTITY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("legalEntityId"));
    }

    @Test
    void assignsALegalEntityToAPolicyAndReturnsTheNewEtag() throws Exception {
        when(service.assignLegalEntity(eq(TENANT), eq(POLICY_ID),
                any(AssignPolicyLegalEntityRequest.class)))
                .thenReturn(policy(LEGAL_ENTITY_ID, 3));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + LEGAL_ENTITY_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.legalEntityId").value(LEGAL_ENTITY_ID.toString()))
                .andExpect(jsonPath("$.version").value(3));

        ArgumentCaptor<AssignPolicyLegalEntityRequest> captor =
                ArgumentCaptor.forClass(AssignPolicyLegalEntityRequest.class);
        verify(service).assignLegalEntity(eq(TENANT), eq(POLICY_ID), captor.capture());
        assertThat(captor.getValue().legalEntityId()).isEqualTo(LEGAL_ENTITY_ID);
    }

    @Test
    void clearsALegalEntityWithANullLegalEntityId() throws Exception {
        when(service.assignLegalEntity(eq(TENANT), eq(POLICY_ID),
                any(AssignPolicyLegalEntityRequest.class)))
                .thenReturn(policy(null, 4));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntityId").value(nullValue()));

        ArgumentCaptor<AssignPolicyLegalEntityRequest> captor =
                ArgumentCaptor.forClass(AssignPolicyLegalEntityRequest.class);
        verify(service).assignLegalEntity(eq(TENANT), eq(POLICY_ID), captor.capture());
        assertThat(captor.getValue().legalEntityId()).isNull();
    }

    @Test
    void returnsNotFoundWhenTheLegalEntityDoesNotExist() throws Exception {
        when(service.assignLegalEntity(eq(TENANT), eq(POLICY_ID),
                any(AssignPolicyLegalEntityRequest.class)))
                .thenThrow(PolicyErrors.notFound("LEGAL_ENTITY_NOT_FOUND", "legalEntityId",
                        "Legal entity not found: " + LEGAL_ENTITY_ID));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + LEGAL_ENTITY_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"));
    }

    private static LegalEntityView view() {
        return new LegalEntityView(LEGAL_ENTITY_ID, "LE-001", "Acme Insurance", "INSURER",
                "US", "NAIC", "LIC-42", "ACTIVE", CREATED);
    }

    private static Policy policy(UUID legalEntityId, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, "DRAFT",
                FROM, TO, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED, null,
                null, legalEntityId);
    }
}
