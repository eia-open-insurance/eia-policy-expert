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

@WebMvcTest(PolicyProgramController.class)
class PolicyProgramControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PROGRAM_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID LEAD_POLICY_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");
    private static final String CREATE_BODY = """
            {"name":"Captive 2026","programType":"CAPTIVE",
             "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PolicyProgramService service;

    @Test
    void createsAProgramUnderTheApiPrefix() throws Exception {
        when(service.create(eq(TENANT), any(CreateProgramRequest.class)))
                .thenReturn(view(LEAD_POLICY_ID));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.programNumber").value("PRG-2026-0000001"))
                .andExpect(jsonPath("$.name").value("Captive 2026"))
                .andExpect(jsonPath("$.programType").value("CAPTIVE"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2027-01-01"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.leadPolicyId").value(LEAD_POLICY_ID.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-19T12:00:00Z"))
                .andExpect(jsonPath("$.capacityAmount").value(nullValue()))
                .andExpect(jsonPath("$.capacityCurrency").value(nullValue()))
                .andExpect(jsonPath("$.usedAmount").value("0.0000"))
                .andExpect(jsonPath("$.utilizationPct").value(nullValue()))
                .andExpect(jsonPath("$.policyCount").value(0))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void createsAProgramWithCapacityAndDerivedUsage() throws Exception {
        when(service.create(eq(TENANT), any(CreateProgramRequest.class)))
                .thenReturn(view(LEAD_POLICY_ID, new BigDecimal("2000000.0000"), "USD",
                        new BigDecimal("750000.0000"), 2));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Capacity Program","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":2000000.0000,"capacityCurrency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacityAmount").value("2000000.0000"))
                .andExpect(jsonPath("$.capacityCurrency").value("USD"))
                .andExpect(jsonPath("$.usedAmount").value("750000.0000"))
                .andExpect(jsonPath("$.utilizationPct").value("37.50"))
                .andExpect(jsonPath("$.policyCount").value(2));

        ArgumentCaptor<CreateProgramRequest> captor =
                ArgumentCaptor.forClass(CreateProgramRequest.class);
        verify(service).create(eq(TENANT), captor.capture());
        assertThat(captor.getValue().capacityAmount())
                .isEqualByComparingTo("2000000.0000");
        assertThat(captor.getValue().capacityCurrency()).isEqualTo("USD");
    }

    @Test
    void rejectsANonPositiveCapacityAmount() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Capacity Program","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":-1,"capacityCurrency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityAmount"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsABadCapacityCurrency() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Capacity Program","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":2000000,"capacityCurrency":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityCurrency"));

        verifyNoInteractions(service);
    }

    @Test
    void acceptsTheTenantHeader() throws Exception {
        when(service.create(eq(TENANT), any(CreateProgramRequest.class)))
                .thenReturn(view(null));

        mvc.perform(post("/api/v1/programs")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leadPolicyId").value(nullValue()));

        verify(service).create(eq(TENANT), any(CreateProgramRequest.class));
    }

    @Test
    void rejectsAMissingTenant() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonDirectBook() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsABlankProgramName() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verifyNoInteractions(service);
    }

    @Test
    void listsPrograms() throws Exception {
        when(service.list(TENANT)).thenReturn(List.of(view(LEAD_POLICY_ID)));

        mvc.perform(get("/api/v1/programs").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$[0].programNumber").value("PRG-2026-0000001"));
    }

    @Test
    void listsProgramsUnderTheInternalAlias() throws Exception {
        when(service.list(TENANT)).thenReturn(List.of());

        mvc.perform(get("/internal/v1/programs").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getsOneProgram() throws Exception {
        when(service.get(TENANT, PROGRAM_ID)).thenReturn(view(null));

        mvc.perform(get("/api/v1/programs/{id}", PROGRAM_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()));
    }

    @Test
    void returnsStructuredNotFoundForAnUnknownProgram() throws Exception {
        when(service.get(TENANT, PROGRAM_ID)).thenThrow(PolicyErrors.notFound(
                "PROGRAM_NOT_FOUND", "programId", "Program not found: " + PROGRAM_ID));

        mvc.perform(get("/api/v1/programs/{id}", PROGRAM_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("programId"));
    }

    @Test
    void assignsAProgramToAPolicyAndReturnsTheNewEtag() throws Exception {
        when(service.assignProgram(eq(TENANT), eq(POLICY_ID), any(AssignPolicyProgramRequest.class)))
                .thenReturn(policy(PROGRAM_ID, 3));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + PROGRAM_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.programId").value(PROGRAM_ID.toString()))
                .andExpect(jsonPath("$.version").value(3));

        ArgumentCaptor<AssignPolicyProgramRequest> captor =
                ArgumentCaptor.forClass(AssignPolicyProgramRequest.class);
        verify(service).assignProgram(eq(TENANT), eq(POLICY_ID), captor.capture());
        assertThat(captor.getValue().programId()).isEqualTo(PROGRAM_ID);
    }

    @Test
    void clearsAProgramWithANullProgramId() throws Exception {
        when(service.assignProgram(eq(TENANT), eq(POLICY_ID), any(AssignPolicyProgramRequest.class)))
                .thenReturn(policy(null, 4));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(nullValue()));

        ArgumentCaptor<AssignPolicyProgramRequest> captor =
                ArgumentCaptor.forClass(AssignPolicyProgramRequest.class);
        verify(service).assignProgram(eq(TENANT), eq(POLICY_ID), captor.capture());
        assertThat(captor.getValue().programId()).isNull();
    }

    @Test
    void returnsNotFoundWhenTheProgramDoesNotExist() throws Exception {
        when(service.assignProgram(eq(TENANT), eq(POLICY_ID), any(AssignPolicyProgramRequest.class)))
                .thenThrow(PolicyErrors.notFound("PROGRAM_NOT_FOUND", "programId",
                        "Program not found: " + PROGRAM_ID));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + PROGRAM_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"));
    }

    private static ProgramView view(UUID leadPolicyId) {
        return view(leadPolicyId, null, null, new BigDecimal("0.0000"), 0);
    }

    private static ProgramView view(UUID leadPolicyId, BigDecimal capacityAmount,
                                    String capacityCurrency, BigDecimal usedAmount,
                                    long policyCount) {
        return new ProgramView(PROGRAM_ID, "PRG-2026-0000001", "Captive 2026", "CAPTIVE",
                FROM, TO, "ACTIVE", capacityAmount, capacityCurrency, leadPolicyId, CREATED,
                usedAmount, ProgramRules.utilizationPct(usedAmount, capacityAmount),
                policyCount);
    }

    private static Policy policy(UUID programId, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "DRAFT",
                FROM, TO, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED, null,
                programId);
    }
}
