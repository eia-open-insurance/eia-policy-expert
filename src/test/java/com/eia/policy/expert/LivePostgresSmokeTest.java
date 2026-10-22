package com.eia.policy.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live PostgreSQL smoke for the second Policy slice.
 *
 * <p>Runs the real Spring service against a real PostgreSQL database and drives
 * the HTTP API through the complete contract:
 * create -> named insured -> issue -> coverage -> endorsement -> erosion ->
 * cancellation -> reinstatement.
 *
 * <p>Opt in with {@code EIA_LIVE_PG=true}. The connection defaults to the local
 * development database and can be overridden with {@code EIA_LIVE_PG_URL},
 * {@code EIA_LIVE_PG_USER}, and {@code EIA_LIVE_PG_PASSWORD}. Every row is
 * tenant-scoped and removed at the end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "EIA_LIVE_PG", matches = "(?i)true")
class LivePostgresSmokeTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSURED_PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIM_ID = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID ALLOCATION_ID = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> environment("EIA_LIVE_PG_URL",
                "jdbc:postgresql://localhost:5432/eia_policy"));
        registry.add("spring.datasource.username", () -> environment("EIA_LIVE_PG_USER", "postgres"));
        registry.add("spring.datasource.password", () -> environment("EIA_LIVE_PG_PASSWORD", "postgres"));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @AfterEach
    void cleanUp() {
        for (String table : new String[]{
                "policy_limit_erosion", "policy_limit_state", "policy_limit", "policy_coverage",
                "policy_endorsement", "policy_insured", "policy_location", "policy_cancellation",
                "policy_reinstatement", "policy_version", "policy_command_idempotency",
                "policy_idempotency", "policy", "product"}) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT);
        }
    }

    @Test
    void issueEndorseCoverageCancelFlow() throws Exception {
        seedActiveProduct(TENANT, "CGL");

        // 1. Create the draft policy, then replay the same request idempotently.
        Map<String, Object> create = Map.of(
                "tenantId", TENANT,
                "productCode", "CGL",
                "insuredPartyId", INSURED_PARTY,
                "effectiveDate", "2026-01-01",
                "expirationDate", "2027-01-01",
                "premiumAmount", "120000.00",
                "premiumCurrency", "USD",
                "limitAmount", "5000000.00",
                "limitCurrency", "USD");
        ResponseEntity<JsonNode> created = send(HttpMethod.POST, "/internal/v1/policies", create,
                null, "live-create-1");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        String policyId = created.getBody().path("id").asText();
        int version = created.getBody().path("version").asInt();
        String etag = created.getHeaders().getETag();

        ResponseEntity<JsonNode> replayed = send(HttpMethod.POST, "/internal/v1/policies", create,
                null, "live-create-1");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replayed.getBody().path("id").asText()).isEqualTo(policyId);

        // 2. A named insured is required before issue. The pre-issue add issues no version.
        Map<String, Object> insured = Map.of(
                "partyId", INSURED_PARTY.toString(),
                "insuredRole", "NAMED_INSURED",
                "displayName", "Acme Chemical Corporation",
                "effectiveFrom", "2026-01-01",
                "effectiveTo", "2027-01-01");
        ResponseEntity<JsonNode> insuredResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/insureds"), insured, etag, "live-insured-1");
        assertThat(insuredResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        etag = insuredResponse.getHeaders().getETag();
        version = version + 1;
        assertThat(insuredResponse.getBody().path("insuredRole").asText())
                .isEqualTo("NAMED_INSURED");

        // 3. Advance DRAFT -> QUOTED -> BOUND -> ISSUED with If-Match on every hop.
        for (String target : new String[]{"QUOTED", "BOUND", "ISSUED"}) {
            ResponseEntity<JsonNode> transition = send(HttpMethod.PATCH,
                    withTenant(policiesPath(policyId) + "/status"), Map.of("status", target), etag, null);
            assertThat(transition.getStatusCode())
                    .as("transition to %s", target)
                    .isEqualTo(HttpStatus.OK);
            assertThat(transition.getBody().path("status").asText()).isEqualTo(target);
            etag = transition.getHeaders().getETag();
            version = version + 1;
        }

        // 4. The current version is derived and its immutable hash verifies.
        ResponseEntity<JsonNode> current = get(withTenant(policiesPath(policyId) + "/versions/current"));
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody().path("hashVerified").asBoolean()).isTrue();
        JsonNode currentVersion = current.getBody().path("version");
        assertThat(currentVersion.path("status").asText()).isEqualTo("ISSUED");
        assertThat(currentVersion.path("versionNumber").asInt()).isEqualTo(1);
        assertThat(currentVersion.path("immutableHash").asText()).hasSize(64);

        // 5. Add a coverage with its limit schedule.
        Map<String, Object> coverage = Map.of(
                "coverageCode", "GL_PRODUCTS",
                "coverageName", "Products liability",
                "coveragePart", "A",
                "occurrenceBasis", "OCCURRENCE",
                "effectiveFrom", "2026-01-01",
                "effectiveTo", "2027-01-01",
                "coinsurancePct", "0.10",
                "limit", Map.of(
                        "perOccurrenceAmount", "2000000.00",
                        "aggregateAmount", "5000000.00",
                        "sublimitAmount", "1000000.00",
                        "deductibleAmount", "5000.00",
                        "coinsurancePct", "0.10",
                        "currency", "USD"));
        ResponseEntity<JsonNode> coverageResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/coverages"), coverage, etag, "live-coverage-1");
        assertThat(coverageResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        etag = coverageResponse.getHeaders().getETag();
        version = version + 1;
        JsonNode coverageBody = coverageResponse.getBody();
        String limitId = coverageBody.path("limit").path("id").asText();
        assertThat(coverageBody.path("coverage").path("coverageCode").asText())
                .isEqualTo("GL_PRODUCTS");
        assertThat(coverageBody.path("limit").path("aggregateAmount").asText())
                .isEqualTo("5000000.0000");
        assertThat(coverageBody.path("state").path("remainingAmount").asText())
                .isEqualTo("5000000.0000");

        ResponseEntity<JsonNode> coverageList = get(withTenant(policiesPath(policyId) + "/coverages"));
        assertThat(coverageList.getBody().path("total").asInt()).isEqualTo(1);

        // 6. Endorse the policy: a new immutable version supersedes version 1.
        ResponseEntity<JsonNode> endorsementResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/endorsements"),
                Map.of("endorsementType", "PREMIUM",
                        "effectiveDate", "2026-06-01",
                        "description", "Premium endorsement for increased exposure",
                        "premiumDeltaAmount", "5000.00",
                        "premiumDeltaCurrency", "USD",
                        "coverageImpact", "BROADEN"), etag, "live-endorse-1");
        assertThat(endorsementResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        etag = endorsementResponse.getHeaders().getETag();
        version = version + 1;
        assertThat(endorsementResponse.getBody().path("endorsementNumber").asText())
                .isEqualTo("END-0002");
        assertThat(endorsementResponse.getBody().path("premiumDeltaAmount").asText())
                .isEqualTo("5000.0000");

        ResponseEntity<JsonNode> versions = get(withTenant(policiesPath(policyId) + "/versions"));
        assertThat(versions.getBody().path("total").asInt()).isEqualTo(3);
        JsonNode newest = versions.getBody().path("items").get(0);
        JsonNode previous = versions.getBody().path("items").get(1);
        assertThat(newest.path("versionNumber").asInt()).isEqualTo(3);
        assertThat(newest.path("changeType").asText()).isEqualTo("ENDORSEMENT");
        assertThat(newest.path("status").asText()).isEqualTo("ISSUED");
        assertThat(newest.path("supersedesVersionId").asText())
                .isEqualTo(previous.path("id").asText());
        assertThat(previous.path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(previous.path("effectiveTo").asText()).isEqualTo("2026-06-01");

        // 7. Erode the aggregate, then replay the same fact idempotently.
        Map<String, Object> erosion = Map.of(
                "limitId", limitId,
                "claimId", CLAIM_ID.toString(),
                "allocationId", ALLOCATION_ID.toString(),
                "sourceVersion", 1,
                "paidAmount", "250000.00",
                "reservedAmount", "50000.00",
                "recoveredAmount", "0.00",
                "asOf", "2026-10-19T12:00:00Z");
        ResponseEntity<JsonNode> erosionResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/erosion"), erosion, null, "live-erosion-1");
        assertThat(erosionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(erosionResponse.getBody().path("remainingAmount").asText())
                .isEqualTo("4700000.0000");
        assertThat(erosionResponse.getBody().path("incurredAmount").asText())
                .isEqualTo("300000.0000");

        ResponseEntity<JsonNode> erosionReplay = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/erosion"), erosion, null, null);
        assertThat(erosionReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(erosionReplay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(erosionReplay.getBody().path("remainingAmount").asText())
                .isEqualTo("4700000.0000");

        ResponseEntity<JsonNode> aggregates = get(withTenant(policiesPath(policyId) + "/aggregate-state"));
        assertThat(aggregates.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(aggregates.getBody().path("items").get(0).path("remainingAmount").asText())
                .isEqualTo("4700000.0000");
        assertThat(aggregates.getBody().path("items").get(0).path("sourceVersion").asLong())
                .isEqualTo(1L);

        // 8. Cancel pro-rata and verify the computed return premium.
        ResponseEntity<JsonNode> cancellationResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/cancel"),
                Map.of("cancellationType", "PRO_RATA",
                        "reason", "Insured sold the business",
                        "effectiveDate", "2026-07-02",
                        "noticeDays", 0), etag, "live-cancel-1");
        assertThat(cancellationResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cancellation = cancellationResponse.getBody();
        assertThat(cancellation.path("termDays").asInt()).isEqualTo(365);
        assertThat(cancellation.path("remainingDays").asInt()).isEqualTo(183);
        assertThat(cancellation.path("proRataPct").asText()).isEqualTo("0.501370");
        assertThat(cancellation.path("returnPremiumAmount").asText()).isEqualTo("60164.4000");
        assertThat(cancellation.path("earnedPremiumAmount").asText()).isEqualTo("59835.6000");
        etag = cancellationResponse.getHeaders().getETag();
        version = version + 1;

        ResponseEntity<JsonNode> cancelledPolicy = get(withTenant(policiesPath(policyId)));
        assertThat(cancelledPolicy.getBody().path("status").asText()).isEqualTo("CANCELLED");

        // 9. Reinstate only with approval and premium evidence.
        ResponseEntity<JsonNode> reinstatementResponse = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/reinstate"),
                Map.of("effectiveDate", "2026-08-01",
                        "reason", "Premium received in full",
                        "approvedBy", APPROVER.toString(),
                        "approvalRef", APPROVAL.toString(),
                        "premiumEvidenceRef", "RCPT-2026-0001",
                        "premiumEvidenceAmount", "1500.00",
                        "premiumEvidenceCurrency", "USD"), etag, "live-reinstate-1");
        assertThat(reinstatementResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reinstatementResponse.getBody().path("status").asText()).isEqualTo("APPROVED");
        assertThat(reinstatementResponse.getBody().path("premiumEvidenceRef").asText())
                .isEqualTo("RCPT-2026-0001");

        ResponseEntity<JsonNode> reinstatedPolicy = get(withTenant(policiesPath(policyId)));
        assertThat(reinstatedPolicy.getBody().path("status").asText()).isEqualTo("REINSTATED");

        // 10. Final state: five versions and four endorsements, current hash verified.
        ResponseEntity<JsonNode> finalVersions = get(withTenant(policiesPath(policyId) + "/versions"));
        assertThat(finalVersions.getBody().path("total").asInt()).isEqualTo(5);
        ResponseEntity<JsonNode> finalEndorsements = get(withTenant(policiesPath(policyId) + "/endorsements"));
        assertThat(finalEndorsements.getBody().path("total").asInt()).isEqualTo(4);
        ResponseEntity<JsonNode> finalCurrent = get(withTenant(policiesPath(policyId) + "/versions/current"));
        assertThat(finalCurrent.getBody().path("hashVerified").asBoolean()).isTrue();
        assertThat(finalCurrent.getBody().path("version").path("changeType").asText())
                .isEqualTo("REINSTATEMENT");
        ResponseEntity<JsonNode> cancellations = get(withTenant(policiesPath(policyId) + "/cancellations"));
        assertThat(cancellations.getBody().path("items").get(0).path("status").asText())
                .isEqualTo("REINSTATED");
        ResponseEntity<JsonNode> reinstatements = get(withTenant(policiesPath(policyId) + "/reinstatements"));
        assertThat(reinstatements.getBody().path("total").asInt()).isEqualTo(1);

        // Stale version guard still protects every command.
        ResponseEntity<JsonNode> stale = send(HttpMethod.POST,
                withTenant(policiesPath(policyId) + "/coverages"), coverage, "\"" + (version - 2) + "\"", null);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().path("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
    }

    private ResponseEntity<JsonNode> send(HttpMethod method, String path, Object body, String etag,
                                          String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            headers.setIfMatch(etag);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        try {
            String json = body == null ? null : objectMapper.writeValueAsString(body);
            return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Request failed: " + method + " " + path, ex);
        }
    }

    private ResponseEntity<JsonNode> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), JsonNode.class);
    }

    private static String policiesPath(String policyId) {
        return "/internal/v1/policies/" + policyId;
    }

    private static String withTenant(String path) {
        return path + "?tenantId=" + TENANT;
    }
}

