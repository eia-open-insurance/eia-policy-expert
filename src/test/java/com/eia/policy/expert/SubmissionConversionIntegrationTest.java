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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end submission conversion on H2: a QUOTED submission becomes a DRAFT
 * policy with its base coverage and limit, both aggregate versions advance, the
 * outbox carries {@code policy.submission.converted}, replay returns the
 * existing policy, and invalid state/body/tenant requests write nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionConversionIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("outbox_event", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_version", "policy", "underwriting_submission",
                "underwriting_submission_number_seq")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void convertsAQuotedSubmissionIntoADraftPolicyWithCoverageAndEvent() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        MvcResult result = mvc.perform(
                        post("/api/v1/policies/submissions/{id}/convert", submissionId)
                                .param("tenantId", TENANT.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"premiumAmount\":125000.00,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.policyNumber").value(
                        org.hamcrest.Matchers.matchesPattern("POL-\\d{4}-\\d{7}")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.productCode").value("CGL_PRODUCTS"))
                .andExpect(jsonPath("$.insuredPartyId").value(APPLICANT.toString()))
                .andExpect(jsonPath("$.effectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.expirationDate").value("2027-11-01"))
                .andExpect(jsonPath("$.premiumAmount").value("125000.0000"))
                .andExpect(jsonPath("$.premiumCurrency").value("USD"))
                .andExpect(jsonPath("$.limitAmount").value("2500000.0000"))
                .andExpect(jsonPath("$.limitCurrency").value("USD"))
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()))
                .andReturn();
        JsonNode policy = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID policyId = UUID.fromString(policy.get("id").asText());
        String policyNumber = policy.get("policyNumber").asText();

        mvc.perform(get("/internal/v1/policies/{id}", policyId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));

        Map<String, Object> policyRow = jdbc.queryForMap(
                "SELECT submission_id, version FROM policy WHERE id = ?", policyId);
        assertThat(policyRow.get("submission_id")).isEqualTo(submissionId);
        assertThat(policyRow.get("version")).isEqualTo(1);

        Map<String, Object> coverage = jdbc.queryForMap("""
                SELECT coverage_code, status
                  FROM policy_coverage WHERE policy_id = ?
                """, policyId);
        assertThat(coverage.get("coverage_code")).isEqualTo("CGL_PRODUCTS");
        assertThat(coverage.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT effective_from FROM policy_coverage WHERE policy_id = ?
                """, LocalDate.class, policyId)).isEqualTo(EFFECTIVE);
        assertThat(jdbc.queryForObject("""
                SELECT effective_to FROM policy_coverage WHERE policy_id = ?
                """, LocalDate.class, policyId)).isEqualTo(EFFECTIVE.plusYears(1));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM policy_limit WHERE policy_id = ?", Integer.class,
                policyId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM policy_limit_state WHERE policy_id = ?", Integer.class,
                policyId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT per_occurrence_amount FROM policy_limit WHERE policy_id = ?
                """, BigDecimal.class, policyId)).isEqualByComparingTo("2500000.0000");

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.policyId").value(policyId.toString()));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].policyId").value(policyId.toString()));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_id, aggregate_version, tenant_id, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event.get("aggregate_type")).isEqualTo("UnderwritingSubmission");
        assertThat(event.get("aggregate_id")).isEqualTo(submissionId);
        assertThat(event.get("aggregate_version")).isEqualTo(3L);
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat((String) event.get("payload"))
                .contains("\"submissionId\":\"" + submissionId + "\"")
                .contains("\"submissionNumber\":\""
                        + submission.get("submissionNumber").asText() + "\"")
                .contains("\"policyId\":\"" + policyId + "\"")
                .contains("\"policyNumber\":\"" + policyNumber + "\"");
    }

    @Test
    void replayingAConversionReturnsTheExistingPolicyAndEmitsNothingNew() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        JsonNode first = convert(submissionId, "{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}",
                TENANT, 201);
        JsonNode second = convert(submissionId,
                "{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}", TENANT, 200);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("policy")).isEqualTo(1);
        assertThat(count("policy_coverage")).isEqualTo(1);
        assertThat(count("policy_limit")).isEqualTo(1);
        assertThat(count("policy_limit_state")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void rejectsReceivedAndTerminalSubmissions() throws Exception {
        UUID received = createSubmission(TENANT);
        convert(received, "{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}", TENANT, 409);

        UUID declined = createSubmission(TENANT);
        transition(declined, "DECLINED", TENANT).andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/policies/submissions/{id}/convert", declined)
                                .param("tenantId", TENANT.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_CONVERTIBLE"))
                .andExpect(jsonPath("$.meta.currentStatus").value("DECLINED"));

        assertThat(count("policy")).isZero();
        assertThat(count("policy_coverage")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isZero();
    }

    @Test
    void validatesTheConversionBodyAndExpirationBeforeWriting() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        List<String> invalidBodies = List.of(
                "{\"premiumCurrency\":\"USD\"}",
                "{\"premiumAmount\":-1,\"premiumCurrency\":\"USD\"}",
                "{\"premiumAmount\":100,\"premiumCurrency\":\"US\"}",
                "{\"premiumAmount\":100,\"premiumCurrency\":\"USD\","
                        + "\"expirationDate\":\"2026-11-01\"}",
                "{\"premiumAmount\":100,\"premiumCurrency\":\"USD\","
                        + "\"expirationDate\":\"2020-01-01\"}"
        );

        for (String body : invalidBodies) {
            MvcResult result = mvc.perform(
                            post("/api/v1/policies/submissions/{id}/convert", submissionId)
                                    .param("tenantId", TENANT.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            JsonNode problem = objectMapper.readTree(
                    result.getResponse().getContentAsString());
            assertThat(problem.get("code").asText()).isIn(
                    "VALIDATION_ERROR", "INVALID_CONVERSION_EXPIRATION");
        }

        assertThat(count("policy")).isZero();
        assertThat(count("policy_coverage")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isZero();
        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.policyId").value(
                        org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void enforcesTheTenantWallOnConversion() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", submissionId)
                        .param("tenantId", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));

        assertThat(count("policy")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isZero();
    }

    @Test
    void honorsAnExplicitExpirationDate() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        convert(submissionId, "{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\","
                + "\"expirationDate\":\"2028-01-15\"}", TENANT, 201);

        mvc.perform(get("/internal/v1/policies")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].expirationDate").value("2028-01-15"));
    }

    @Test
    void convertsABoundSubmissionWithoutAPolicyAndBumpsItsVersion() throws Exception {
        JsonNode submission = quotedSubmission();
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());
        transition(submissionId, "BOUND", TENANT).andExpect(status().isOk());

        convert(submissionId, "{\"premiumAmount\":125000,\"premiumCurrency\":\"USD\"}", TENANT,
                201);

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.policyId").exists());
        assertThat(count("policy")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isEqualTo(1);
    }

    private JsonNode quotedSubmission() throws Exception {
        UUID submissionId = createSubmission(TENANT);
        transition(submissionId, "QUOTED", TENANT).andExpect(status().isOk());
        return objectMapper.readTree(mvc.perform(
                        get("/api/v1/policies/submissions/{id}", submissionId)
                                .param("tenantId", TENANT.toString()))
                .andReturn().getResponse().getContentAsString());
    }

    private UUID createSubmission(UUID tenantId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicantPartyId":"%s","productCode":"CGL_PRODUCTS",
                                 "requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":2500000.00,
                                 "requestedLimitCurrency":"USD",
                                 "tortCodes":["ASBESTOS"]}
                                """.formatted(APPLICANT)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).get("submissionId").asText());
    }

    private JsonNode convert(UUID submissionId, String body, UUID tenantId, int expectedStatus)
            throws Exception {
        MvcResult result = mvc.perform(
                        post("/api/v1/policies/submissions/{id}/convert", submissionId)
                                .param("tenantId", tenantId.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            UUID submissionId, String status, UUID tenantId) throws Exception {
        return mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
