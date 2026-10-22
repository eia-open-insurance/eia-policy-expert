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
 * End-to-end legal entity catalog flow on H2: create/list/get, assign/clear, the
 * tenant wall, the policy projection, and the two transactional outbox events.
 * The rollback tests prove a failed command leaves neither the row nor the
 * event behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalEntityIntegrationTest extends ProductCatalogTestSupport {
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
    private LegalEntityService legalEntityService;

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
                "policy_idempotency", "policy", "policy_program", "legal_entity")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsListsGetsAssignsAndClearsALegalEntity() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-LE-0001");
        UUID legalEntityId = createLegalEntity(TENANT, "le-001", "Acme Insurance", "insurer",
                "us", "NAIC", "LIC-42");

        mvc.perform(get("/api/v1/legal-entities").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].legalEntityId").value(legalEntityId.toString()))
                .andExpect(jsonPath("$[0].code").value("LE-001"))
                .andExpect(jsonPath("$[0].entityType").value("INSURER"))
                .andExpect(jsonPath("$[0].jurisdiction").value("US"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mvc.perform(get("/api/v1/legal-entities/{id}", legalEntityId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntityId").value(legalEntityId.toString()))
                .andExpect(jsonPath("$.name").value("Acme Insurance"))
                .andExpect(jsonPath("$.regulator").value("NAIC"))
                .andExpect(jsonPath("$.licenseNumber").value("LIC-42"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mvc.perform(get("/internal/v1/legal-entities/{id}", legalEntityId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LE-001"));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", policy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + legalEntityId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.legalEntityId").value(legalEntityId.toString()))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/internal/v1/policies/{id}", policy.id())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalEntityId").value(legalEntityId.toString()));

        mvc.perform(get("/internal/v1/policies").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].legalEntityId").value(legalEntityId.toString()));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", policy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.legalEntityId").value(nullValue()))
                .andExpect(jsonPath("$.version").value(2));

        Policy reloaded = policyService.get(TENANT, policy.id());
        assertThat(reloaded.legalEntityId()).isNull();
        assertThat(reloaded.version()).isEqualTo(2);
    }

    @Test
    void writesCreatedAndAssignedEventsInTheSameTransaction() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-LE-0002");
        UUID legalEntityId = createLegalEntity(TENANT, "LE-EVENT", "Fronting Co", "FRONTER",
                "BM", "BMA", "FL-7");
        assign(policy.id(), legalEntityId);
        clear(policy.id());

        List<Map<String, Object>> created = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, tenant_id, payload, partition_key
                  FROM outbox_event
                 WHERE event_type = 'policy.legal-entity.created'
                """);
        assertThat(created).hasSize(1);
        Map<String, Object> createdEvent = created.get(0);
        assertThat(createdEvent.get("aggregate_type")).isEqualTo("LegalEntity");
        assertThat(createdEvent.get("aggregate_version")).isEqualTo(1L);
        assertThat(createdEvent.get("tenant_id")).isEqualTo(TENANT);
        assertThat(createdEvent.get("partition_key")).isEqualTo(legalEntityId.toString());
        assertThat((String) createdEvent.get("payload"))
                .contains("\"legalEntityId\":\"" + legalEntityId + "\"")
                .contains("\"code\":\"LE-EVENT\"")
                .contains("\"entityType\":\"FRONTER\"")
                .contains("\"jurisdiction\":\"BM\"")
                .contains("\"regulator\":\"BMA\"")
                .contains("\"licenseNumber\":\"FL-7\"")
                .contains("\"status\":\"ACTIVE\"");

        List<Map<String, Object>> assigned = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.policy.legal-entity-assigned'
                 ORDER BY event_sequence
                """);
        assertThat(assigned).hasSize(2);
        assertThat(assigned).extracting(event -> event.get("aggregate_type"))
                .containsExactly("Policy", "Policy");
        assertThat(assigned).extracting(event -> event.get("aggregate_version"))
                .containsExactly(2L, 3L);
        assertThat((String) assigned.get(0).get("payload"))
                .contains("\"legalEntityId\":\"" + legalEntityId + "\"")
                .contains("\"previousLegalEntityId\":null")
                .contains("\"changedFields\":[\"legalEntityId\"]");
        assertThat((String) assigned.get(1).get("payload"))
                .contains("\"legalEntityId\":null")
                .contains("\"previousLegalEntityId\":\"" + legalEntityId + "\"");
    }

    @Test
    void enforcesTheTenantWallOnLegalEntitiesAndAssignment() throws Exception {
        UUID legalEntityId = createLegalEntity(TENANT, "LE-WALL", "Wall Insurance", "INSURER",
                "US", null, null);
        Policy otherPolicy = createPolicy(OTHER_TENANT, "POL-LE-OTHER");

        mvc.perform(get("/api/v1/legal-entities/{id}", legalEntityId)
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"));

        mvc.perform(get("/api/v1/legal-entities").param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", otherPolicy.id())
                        .param("tenantId", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + legalEntityId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"));

        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", otherPolicy.id())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + legalEntityId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));

        Policy reloaded = policyService.get(OTHER_TENANT, otherPolicy.id());
        assertThat(reloaded.legalEntityId()).isNull();
        assertThat(reloaded.version()).isZero();
    }

    @Test
    void validatesTheCatalogFieldsAndRejectsDuplicateCodes() throws Exception {
        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LE-BAD","name":"Bad Type","entityType":"REINSURER",
                                 "jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEGAL_ENTITY_TYPE"))
                .andExpect(jsonPath("$.errors[0].field").value("entityType"));

        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LE-BAD","name":"Bad Jurisdiction","entityType":"INSURER",
                                 "jurisdiction":"USA"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LE-BAD","name":" ","entityType":"INSURER",
                                 "jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        assertThat(count("legal_entity")).isZero();
        assertThat(count("outbox_event")).isZero();

        createLegalEntity(TENANT, "LE-DUP", "First Copy", "MGA", "US", null, null);

        mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"le-dup","name":"Second Copy","entityType":"MGA",
                                 "jurisdiction":"US"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LEGAL_ENTITY_CODE"))
                .andExpect(jsonPath("$.errors[0].field").value("code"));

        assertThat(count("legal_entity")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void legalEntityCreateRollbackLeavesNoRowAndNoEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            legalEntityService.create(TENANT, new CreateLegalEntityRequest(
                    "LE-ROLLBACK", "Rollback Insurance", "INSURER", "US", null, null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("legal_entity")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void assignmentRollbackKeepsThePolicyUnassignedAndWritesNoEvent() throws Exception {
        Policy policy = createPolicy(TENANT, "POL-LE-ROLLBACK");
        UUID legalEntityId = createLegalEntity(TENANT, "LE-ROLLBACK", "Rollback Insurance",
                "INSURER", "US", null, null);
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            legalEntityService.assignLegalEntity(TENANT, policy.id(),
                    new AssignPolicyLegalEntityRequest(legalEntityId));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Policy reloaded = policyService.get(TENANT, policy.id());
        assertThat(reloaded.legalEntityId()).isNull();
        assertThat(reloaded.version()).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.policy.legal-entity-assigned'
                """, Integer.class)).isZero();
    }

    private UUID createLegalEntity(UUID tenantId, String code, String name, String entityType,
                                   String jurisdiction, String regulator, String licenseNumber)
            throws Exception {
        String regulatorPart = regulator == null
                ? "" : ",\"regulator\":\"" + regulator + "\"";
        String licensePart = licenseNumber == null
                ? "" : ",\"licenseNumber\":\"" + licenseNumber + "\"";
        String body = "{\"code\":\"" + code + "\",\"name\":\"" + name
                + "\",\"entityType\":\"" + entityType + "\",\"jurisdiction\":\""
                + jurisdiction + "\"" + regulatorPart + licensePart + "}";
        MvcResult result = mvc.perform(post("/api/v1/legal-entities")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        return UUID.fromString(json.get("legalEntityId").asText());
    }

    private void assign(UUID policyId, UUID legalEntityId) throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", policyId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + legalEntityId + "\"}"))
                .andExpect(status().isOk());
    }

    private void clear(UUID policyId) throws Exception {
        mvc.perform(patch("/api/v1/policies/{policyId}/legal-entity", policyId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":null}"))
                .andExpect(status().isOk());
    }

    private Policy createPolicy(UUID tenantId, String policyNumber) {
        return policyService.create(new CreatePolicyRequest(tenantId, policyNumber, "CGL", PARTY,
                null, FROM, TO, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
