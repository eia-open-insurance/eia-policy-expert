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
 * Idempotency-key replay for the two response-bearing policy commands:
 * {@code POST /policies/{id}/rating} and
 * {@code POST /policies/submissions/{id}/convert}.
 *
 * <p>A first keyed call is a normal {@code 201}. A replay returns {@code 200}
 * with the stored body, the original ETag-bearing aggregate version, and
 * {@code Idempotent-Replayed: true}. Keys are scoped by tenant and operation,
 * a different key performs a new command, an absent key keeps the old
 * behaviour, and an over-long key is rejected before any write.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyIdempotencyReplayIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String RATING_BODY = """
            {"ratingMethod":"RATE_TIMES_EXPOSURE","baseRate":0.0125,
             "exposureAmount":1000000,"factor":1.25,"apply":true}
            """;
    private static final String CONVERSION_BODY =
            "{\"premiumAmount\":125000.00,\"premiumCurrency\":\"USD\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("outbox_event", "policy_rating_worksheet",
                "policy_limit_state", "policy_limit", "policy_coverage", "policy_version",
                "policy", "underwriting_submission", "underwriting_submission_number_seq",
                "policy_command_idempotency", "policy_idempotency")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void policyCreationIdempotencyStillWorksOnTheSharedTable() throws Exception {
        String body = policyCreateBody(TENANT, "POL-IDEM-CREATE-1");

        MvcResult first = mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "create-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();
        JsonNode firstBody = body(first);

        MvcResult replay = mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "create-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(body(replay).get("id").asText())
                .isEqualTo(firstBody.get("id").asText());
        assertThat(count("policy")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT operation, request_hash, policy_id
                  FROM policy_idempotency
                 WHERE tenant_id = ? AND idempotency_key = 'create-key-1'
                """, TENANT);
        assertThat(row.get("operation")).isEqualTo("create");
        assertThat(row.get("request_hash")).isNotNull();
        assertThat(row.get("policy_id").toString())
                .isEqualTo(firstBody.get("id").asText());
    }

    @Test
    void theSameKeyMayBeUsedForDifferentOperations() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-OPERATION-SCOPE");
        UUID submissionId = quotedSubmission(TENANT);

        rate(ratingPath(policy.get("id").asText()), TENANT, "shared-operation-key", 201);
        convert(submissionId, TENANT, "shared-operation-key", 201);

        assertThat(countWhere("idempotency_key = 'shared-operation-key'")).isEqualTo(2);
        assertThat(countWhere("operation = 'rating' AND idempotency_key = 'shared-operation-key'"))
                .isEqualTo(1);
        assertThat(countWhere(
                "operation = 'conversion' AND idempotency_key = 'shared-operation-key'"))
                .isEqualTo(1);
    }

    @Test
    void ratingReplayReturnsTheStoredWorksheetPolicyAndEtag() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-RATE-1");
        String path = ratingPath(policy.get("id").asText());

        MvcResult first = rate(path, TENANT, "rating-key-1", 201);
        assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        JsonNode firstBody = body(first);

        // A second command with another key moves the policy to version 2; the
        // replay must still deliver the first response and its version-1 ETag.
        MvcResult second = rate(path, TENANT, "rating-key-2", 201);
        assertThat(second.getResponse().getHeader("ETag")).isEqualTo("\"2\"");

        MvcResult replay = rate(path, TENANT, "rating-key-1", 200);
        assertThat(replay.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(firstBody);
        assertThat(count("policy_rating_worksheet")).isEqualTo(2);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT operation, response_status, response_json
                  FROM policy_idempotency
                 WHERE tenant_id = ? AND operation = 'rating' AND idempotency_key = ?
                """, TENANT, "rating-key-1");
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat((String) row.get("response_json"))
                .contains(firstBody.get("worksheet").get("id").asText())
                .contains("\"policy\"");
    }

    @Test
    void replayingACalculationOnlyRatingKeepsTheBodyAndOmitsTheEtag() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-RATE-CALC");
        String path = ratingPath(policy.get("id").asText());
        String calculationOnly = """
                {"ratingMethod":"FLAT","baseRate":5000,"factor":1.1,"apply":false}
                """;

        MvcResult first = mvc.perform(post(path)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "calculation-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calculationOnly))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("ETag"))
                .andReturn();

        MvcResult replay = mvc.perform(post(path)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "calculation-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calculationOnly))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().doesNotExist("ETag"))
                .andReturn();

        assertThat(body(replay)).isEqualTo(body(first));
        assertThat(count("policy_rating_worksheet")).isEqualTo(1);
    }

    @Test
    void aDifferentRatingKeyCreatesANewWorksheet() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-RATE-2");
        String path = ratingPath(policy.get("id").asText());

        JsonNode first = body(rate(path, TENANT, "rating-key-a", 201));
        JsonNode second = body(rate(path, TENANT, "rating-key-b", 201));

        assertThat(second.get("worksheet").get("id").asText())
                .isNotEqualTo(first.get("worksheet").get("id").asText());
        assertThat(count("policy_rating_worksheet")).isEqualTo(2);
    }

    @Test
    void ratingKeysAreScopedByTenant() throws Exception {
        JsonNode policyA = createPolicy(TENANT, "POL-IDEM-RATE-TENANT-A");
        JsonNode policyB = createPolicy(OTHER_TENANT, "POL-IDEM-RATE-TENANT-B");

        JsonNode firstA = body(rate(ratingPath(policyA.get("id").asText()), TENANT,
                "shared-rating-key", 201));
        JsonNode firstB = body(rate(ratingPath(policyB.get("id").asText()), OTHER_TENANT,
                "shared-rating-key", 201));

        assertThat(firstB.get("worksheet").get("id").asText())
                .isNotEqualTo(firstA.get("worksheet").get("id").asText());
        assertThat(countWhere("operation = 'rating' AND idempotency_key = 'shared-rating-key'"))
                .isEqualTo(2);

        JsonNode replayA = body(rate(ratingPath(policyA.get("id").asText()), TENANT,
                "shared-rating-key", 200));
        assertThat(replayA).isEqualTo(firstA);
    }

    @Test
    void ratingWithoutAKeyStillRunsEveryTime() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-RATE-NO-KEY");
        String path = ratingPath(policy.get("id").asText());

        rate(path, TENANT, null, 201);
        rate(path, TENANT, null, 201);

        assertThat(count("policy_rating_worksheet")).isEqualTo(2);
        assertThat(countWhere("operation = 'rating'")).isZero();
    }

    @Test
    void anOverlongRatingKeyIsRejectedBeforeAnyWrite() throws Exception {
        JsonNode policy = createPolicy(TENANT, "POL-IDEM-RATE-BAD-KEY");

        mvc.perform(post(ratingPath(policy.get("id").asText()))
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "k".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RATING_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        assertThat(count("policy_rating_worksheet")).isZero();
        assertThat(countWhere("operation = 'rating'")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM policy WHERE tenant_id = ? AND id = ?", Integer.class,
                TENANT, UUID.fromString(policy.get("id").asText()))).isZero();
    }

    @Test
    void conversionReplayReturnsTheStoredPolicyAndEtag() throws Exception {
        UUID submissionId = quotedSubmission(TENANT);

        MvcResult first = convert(submissionId, TENANT, "conversion-key-1", 201);
        assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        JsonNode firstBody = body(first);

        // Moving the converted policy to version 2 must not leak into the
        // stored conversion response or its ETag.
        rate(ratingPath(firstBody.get("id").asText()), TENANT, null, 201);

        MvcResult replay = convert(submissionId, TENANT, "conversion-key-1", 200);
        assertThat(replay.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(firstBody);
        assertThat(count("policy")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE event_type = 'policy.submission.converted'
                """, Integer.class)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT operation, response_status
                  FROM policy_idempotency
                 WHERE tenant_id = ? AND operation = 'conversion'
                   AND idempotency_key = 'conversion-key-1'
                """, TENANT);
        assertThat(row.get("response_status")).isEqualTo(201);
    }

    @Test
    void differentConversionKeysOnDifferentSubmissionsCreatePolicies() throws Exception {
        UUID firstSubmission = quotedSubmission(TENANT);
        UUID secondSubmission = quotedSubmission(TENANT);

        JsonNode firstPolicy = body(convert(firstSubmission, TENANT, "conversion-key-a", 201));
        JsonNode secondPolicy = body(convert(secondSubmission, TENANT, "conversion-key-b", 201));

        assertThat(secondPolicy.get("id").asText())
                .isNotEqualTo(firstPolicy.get("id").asText());
        assertThat(count("policy")).isEqualTo(2);
    }

    @Test
    void conversionKeysAreScopedByTenant() throws Exception {
        UUID submissionA = quotedSubmission(TENANT);
        UUID submissionB = quotedSubmission(OTHER_TENANT);

        JsonNode firstA = body(convert(submissionA, TENANT, "shared-conversion-key", 201));
        JsonNode firstB = body(convert(submissionB, OTHER_TENANT, "shared-conversion-key", 201));

        assertThat(firstB.get("id").asText()).isNotEqualTo(firstA.get("id").asText());
        assertThat(count("policy")).isEqualTo(2);
        assertThat(countWhere(
                "operation = 'conversion' AND idempotency_key = 'shared-conversion-key'"))
                .isEqualTo(2);

        assertThat(body(convert(submissionA, TENANT, "shared-conversion-key", 200)))
                .isEqualTo(firstA);
        assertThat(body(convert(submissionB, OTHER_TENANT, "shared-conversion-key", 200)))
                .isEqualTo(firstB);
    }

    @Test
    void conversionWithoutAKeyKeepsTheExistingStateReplay() throws Exception {
        UUID submissionId = quotedSubmission(TENANT);

        JsonNode first = body(convert(submissionId, TENANT, null, 201));
        JsonNode second = body(convert(submissionId, TENANT, null, 200));

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("policy")).isEqualTo(1);
        assertThat(countWhere("operation = 'conversion'")).isZero();
    }

    @Test
    void anOverlongConversionKeyIsRejectedBeforeAnyWrite() throws Exception {
        UUID submissionId = quotedSubmission(TENANT);

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", submissionId)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "k".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONVERSION_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        assertThat(count("policy")).isZero();
        assertThat(countWhere("operation = 'conversion'")).isZero();
        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.policyId").value(
                        org.hamcrest.Matchers.nullValue()));
    }

    private JsonNode createPolicy(UUID tenantId, String policyNumber) throws Exception {
        MvcResult result = mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyCreateBody(tenantId, policyNumber)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private static String policyCreateBody(UUID tenantId, String policyNumber) {
        return """
                {"tenantId":"%s","policyNumber":"%s","productCode":"CGL",
                 "insuredPartyId":"%s","effectiveDate":"2026-01-01",
                 "expirationDate":"2027-01-01","premiumAmount":120000.00,
                 "premiumCurrency":"USD","limitAmount":5000000.00,
                 "limitCurrency":"USD"}
                """.formatted(tenantId, policyNumber, PARTY);
    }

    private MvcResult rate(String path, UUID tenantId, String idempotencyKey,
                           int expectedStatus) throws Exception {
        var request = post(path)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(RATING_BODY);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private UUID quotedSubmission(UUID tenantId) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicantPartyId":"%s","productCode":"CGL_PRODUCTS",
                                 "requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":2500000.00,
                                 "requestedLimitCurrency":"USD",
                                 "tortCodes":["ASBESTOS"]}
                                """.formatted(PARTY)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID submissionId = UUID.fromString(
                body(created).get("submissionId").asText());
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTED\"}"))
                .andExpect(status().isOk());
        return submissionId;
    }

    private MvcResult convert(UUID submissionId, UUID tenantId, String idempotencyKey,
                              int expectedStatus) throws Exception {
        var request = post("/api/v1/policies/submissions/{id}/convert", submissionId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONVERSION_BODY);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String ratingPath(String policyId) {
        return "/api/v1/policies/" + policyId + "/rating";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countWhere(String predicate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM policy_idempotency WHERE " + predicate, Integer.class);
    }
}
