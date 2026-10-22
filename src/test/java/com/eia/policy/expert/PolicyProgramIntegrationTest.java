package com.eia.policy.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end program catalog flow on H2: create/list/get, assign/clear, the
 * tenant wall, the policy projection, and the two transactional outbox events.
 * The rollback tests prove a failed command leaves neither the row nor the
 * event behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyProgramIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyProgramService programService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of(
                "outbox_event", "policy_version", "policy_command_idempotency",
                "policy_idempotency", "policy", "policy_program")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsListsGetsAssignsAndClearsAProgram() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-PRG-0001");
        UUID programId = createProgram(TENANT, "Captive 2026", "CAPTIVE", FROM, TO,
                policy.id());

        mvc.perform(get("/api/v1/programs").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].programId").value(programId.toString()))
                .andExpect(jsonPath("$[0].programType").value("CAPTIVE"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mvc.perform(get("/api/v1/programs/{id}", programId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programNumber").value(org.hamcrest.Matchers
                        .startsWith("PRG-")))
                .andExpect(jsonPath("$.name").value("Captive 2026"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2027-01-01"))
                .andExpect(jsonPath("$.leadPolicyId").value(policy.id().toString()));

        mvc.perform(get("/internal/v1/programs/{id}", programId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(programId.toString()));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", policy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + programId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.programId").value(programId.toString()))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/internal/v1/policies/{id}", policy.id())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(programId.toString()));

        mvc.perform(get("/internal/v1/policies").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].programId").value(programId.toString()));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", policy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.programId").value(nullValue()))
                .andExpect(jsonPath("$.version").value(2));

        Policy reloaded = policyService.get(TENANT, policy.id());
        assertThat(reloaded.programId()).isNull();
        assertThat(reloaded.version()).isEqualTo(2);
    }

    @Test
    void createsAProgramWithAndWithoutCapacity() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"No Capacity Program","programType":"POOL",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacityAmount").value(nullValue()))
                .andExpect(jsonPath("$.capacityCurrency").value(nullValue()))
                .andExpect(jsonPath("$.usedAmount").value("0.0000"))
                .andExpect(jsonPath("$.utilizationPct").value(nullValue()))
                .andExpect(jsonPath("$.policyCount").value(0));

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
                .andExpect(jsonPath("$.usedAmount").value("0.0000"))
                .andExpect(jsonPath("$.utilizationPct").value("0.00"))
                .andExpect(jsonPath("$.policyCount").value(0));
    }

    @Test
    void computesCapacityUsageFromAssignedPolicies() throws Exception {
        UUID programId = createProgram(TENANT, "Capacity Program", "CAPTIVE", FROM, TO, null,
                new BigDecimal("2000000.00"), "USD");
        Policy first = createPolicy(TENANT, "POL-CAP-0001", new BigDecimal("500000.00"));
        Policy second = createPolicy(TENANT, "POL-CAP-0002", new BigDecimal("250000.00"));
        createPolicy(TENANT, "POL-CAP-UNASSIGNED", new BigDecimal("999999.00"));
        assign(first.id(), programId);
        assign(second.id(), programId);

        mvc.perform(get("/api/v1/programs/{id}", programId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityAmount").value("2000000.0000"))
                .andExpect(jsonPath("$.capacityCurrency").value("USD"))
                .andExpect(jsonPath("$.usedAmount").value("750000.0000"))
                .andExpect(jsonPath("$.utilizationPct").value("37.50"))
                .andExpect(jsonPath("$.policyCount").value(2));

        mvc.perform(get("/api/v1/programs").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].programId").value(programId.toString()))
                .andExpect(jsonPath("$[0].usedAmount").value("750000.0000"))
                .andExpect(jsonPath("$[0].utilizationPct").value("37.50"))
                .andExpect(jsonPath("$[0].policyCount").value(2));
    }

    @Test
    void capacityUsageIsTenantScoped() throws Exception {
        UUID programId = createProgram(TENANT, "Tenant Capacity Program", "FRONTING", FROM, TO,
                null, new BigDecimal("1000000.00"), "USD");
        Policy tenantPolicy = createPolicy(TENANT, "POL-CAP-TENANT",
                new BigDecimal("250000.00"));
        assign(tenantPolicy.id(), programId);
        Policy otherPolicy = createPolicy(OTHER_TENANT, "POL-CAP-OTHER",
                new BigDecimal("900000.00"));
        // The assignment API refuses a foreign program, so link the foreign
        // policy directly to prove the usage queries filter by tenant.
        jdbc.update("UPDATE policy SET program_id = ? WHERE tenant_id = ? AND id = ?",
                programId, OTHER_TENANT, otherPolicy.id());

        mvc.perform(get("/api/v1/programs/{id}", programId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedAmount").value("250000.0000"))
                .andExpect(jsonPath("$.utilizationPct").value("25.00"))
                .andExpect(jsonPath("$.policyCount").value(1));

        mvc.perform(get("/api/v1/programs/{id}", programId)
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"));

        mvc.perform(get("/api/v1/programs").param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void validatesCapacityAmountAndCurrency() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Zero Capacity","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":0,"capacityCurrency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityAmount"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Negative Capacity","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":-1,"capacityCurrency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityAmount"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad Currency","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":1000000,"capacityCurrency":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityCurrency"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lowercase Currency","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":1000000,"capacityCurrency":"usd"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityCurrency"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Amount Only","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityAmount":1000000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROGRAM_CAPACITY"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityCurrency"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Currency Only","programType":"CAPTIVE",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01",
                                 "capacityCurrency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROGRAM_CAPACITY"))
                .andExpect(jsonPath("$.errors[0].field").value("capacityAmount"));

        assertThat(count("policy_program")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void writesProgramCreatedAndProgramAssignedEventsInTheSameTransaction() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-PRG-0002");
        UUID programId = createProgram(TENANT, "Mass Tort Program", "MASS_TORT", FROM, TO,
                null);
        assign(policy.id(), programId);
        clear(policy.id());

        List<Map<String, Object>> created = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, tenant_id, payload, partition_key
                  FROM outbox_event
                 WHERE event_type = 'policy.program.created'
                """);
        assertThat(created).hasSize(1);
        Map<String, Object> createdEvent = created.get(0);
        assertThat(createdEvent.get("aggregate_type")).isEqualTo("Program");
        assertThat(createdEvent.get("aggregate_version")).isEqualTo(1L);
        assertThat(createdEvent.get("tenant_id")).isEqualTo(TENANT);
        assertThat(createdEvent.get("partition_key")).isEqualTo(programId.toString());
        assertThat((String) createdEvent.get("payload"))
                .contains("\"programId\":\"" + programId + "\"")
                .contains("\"programType\":\"MASS_TORT\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"leadPolicyId\":null");

        List<Map<String, Object>> assigned = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.policy.program-assigned'
                 ORDER BY event_sequence
                """);
        assertThat(assigned).hasSize(2);
        assertThat(assigned).extracting(event -> event.get("aggregate_type"))
                .containsExactly("Policy", "Policy");
        assertThat(assigned).extracting(event -> event.get("aggregate_version"))
                .containsExactly(2L, 3L);
        assertThat((String) assigned.get(0).get("payload"))
                .contains("\"programId\":\"" + programId + "\"")
                .contains("\"previousProgramId\":null")
                .contains("\"changedFields\":[\"programId\"]");
        assertThat((String) assigned.get(1).get("payload"))
                .contains("\"programId\":null")
                .contains("\"previousProgramId\":\"" + programId + "\"");
    }

    @Test
    void enforcesTheTenantWallOnProgramsAndAssignment() throws Exception {
        UUID programId = createProgram(TENANT, "Pool Program", "POOL", FROM, TO, null);
        Policy otherPolicy = createPolicy(OTHER_TENANT, "POL-PRG-OTHER");

        mvc.perform(get("/api/v1/programs/{id}", programId)
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"));

        mvc.perform(get("/api/v1/programs").param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(patch("/api/v1/policies/{policyId}/program", otherPolicy.id())
                        .param("tenantId", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + programId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"));

        mvc.perform(patch("/api/v1/policies/{policyId}/program", otherPolicy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + programId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));

        Policy reloaded = policyService.get(OTHER_TENANT, otherPolicy.id());
        assertThat(reloaded.programId()).isNull();
        assertThat(reloaded.version()).isZero();
    }

    @Test
    void validatesTheProgramTypeAndInclusiveDateRange() throws Exception {
        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Backwards","programType":"CAPTIVE",
                                 "effectiveFrom":"2027-01-01","effectiveTo":"2026-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROGRAM_DATES"))
                .andExpect(jsonPath("$.errors[0].field").value("effectiveTo"));

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Unknown","programType":"SELF_INSURED",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROGRAM_TYPE"));

        assertThat(count("policy_program")).isZero();
        assertThat(count("outbox_event")).isZero();

        mvc.perform(post("/api/v1/programs")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"One day","programType":"FRONTING",
                                 "effectiveFrom":"2026-06-01","effectiveTo":"2026-06-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-06-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-06-01"));
    }

    @Test
    void programCreateRollbackLeavesNoRowAndNoEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            programService.create(TENANT, new CreateProgramRequest("Rollback Program", "CAPTIVE",
                    FROM, TO, null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy_program")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void assignmentRollbackKeepsThePolicyUnassignedAndWritesNoEvent() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-PRG-ROLLBACK");
        UUID programId = createProgram(TENANT, "Rollback Assignment Program", "COINSURANCE",
                FROM, TO, null);
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            programService.assignProgram(TENANT, policy.id(),
                    new AssignPolicyProgramRequest(programId));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Policy reloaded = policyService.get(TENANT, policy.id());
        assertThat(reloaded.programId()).isNull();
        assertThat(reloaded.version()).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.policy.program-assigned'
                """, Integer.class)).isZero();
    }

    private UUID createProgram(UUID tenantId, String name, String programType,
                               LocalDate effectiveFrom, LocalDate effectiveTo,
                               UUID leadPolicyId) throws Exception {
        return createProgram(tenantId, name, programType, effectiveFrom, effectiveTo,
                leadPolicyId, null, null);
    }

    private UUID createProgram(UUID tenantId, String name, String programType,
                               LocalDate effectiveFrom, LocalDate effectiveTo,
                               UUID leadPolicyId, BigDecimal capacityAmount,
                               String capacityCurrency) throws Exception {
        String lead = leadPolicyId == null ? "" : ",\"leadPolicyId\":\"" + leadPolicyId + "\"";
        String capacity = capacityAmount == null ? ""
                : ",\"capacityAmount\":" + capacityAmount.toPlainString()
                        + ",\"capacityCurrency\":\"" + capacityCurrency + "\"";
        String body = "{\"name\":\"" + name + "\",\"programType\":\"" + programType
                + "\",\"effectiveFrom\":\"" + effectiveFrom + "\",\"effectiveTo\":\""
                + effectiveTo + "\"" + lead + capacity + "}";
        MvcResult result = mvc.perform(post("/api/v1/programs")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("programNumber").asText()).startsWith("PRG-");
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        return UUID.fromString(json.get("programId").asText());
    }

    private void assign(UUID policyId, UUID programId) throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/program", policyId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + programId + "\"}"))
                .andExpect(status().isOk());
    }

    private void clear(UUID policyId) throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/program", policyId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":null}"))
                .andExpect(status().isOk());
    }

    private Policy createPolicy(UUID tenantId, String policyNumber) {
        return createPolicy(tenantId, policyNumber, new BigDecimal("5000000.00"));
    }

    private Policy createPolicy(UUID tenantId, String policyNumber, BigDecimal limitAmount) {
        return policyService.create(new CreatePolicyRequest(tenantId, policyNumber, "CGL", PARTY,
                null, FROM, TO, new BigDecimal("120000.00"), "USD",
                limitAmount, "USD"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
