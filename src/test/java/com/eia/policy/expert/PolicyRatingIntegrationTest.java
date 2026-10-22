package com.eia.policy.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end rating slice against H2: both methods, the DRAFT/QUOTED gate,
 * input validation, calculation-only versus applied commands, premium and
 * version movement, the rated outbox event, latest-worksheet reads, hash
 * reproducibility, and the tenant wall.
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyRatingIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final String EVENT_TYPE = "policy.policy.rated";

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyRatingService ratingService;

    @Autowired
    private RatingWorksheetRepository worksheetRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "outbox_event", "billing_dunning_action", "policy_rating_worksheet",
                "policy_credit_note", "policy_mass_tort_profile", "policy_installment",
                "policy_billing_plan", "policy_limit_erosion", "policy_limit_state",
                "policy_limit", "policy_coverage", "policy_endorsement", "policy_insured",
                "policy_location", "policy_cancellation", "policy_reinstatement",
                "policy_version", "policy_command_idempotency", "policy_idempotency",
                "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void calculatesRateTimesExposureWithoutTouchingThePolicy() {
        Policy policy = createPolicy("POL-RATE-0001");

        RatingResult result = ratingService.rate(TENANT, policy.id(),
                rtx("0.0125", "1000000", "1.25", false), null);

        PolicyRatingWorksheet worksheet = result.worksheet();
        assertThat(result.policy()).isNull();
        assertThat(worksheet.id()).isNotNull();
        assertThat(worksheet.tenantId()).isEqualTo(TENANT);
        assertThat(worksheet.policyId()).isEqualTo(policy.id());
        assertThat(worksheet.ratingMethod()).isEqualTo("RATE_TIMES_EXPOSURE");
        assertThat(worksheet.baseRate()).isEqualByComparingTo("0.012500");
        assertThat(worksheet.exposureAmount()).isEqualByComparingTo("1000000.0000");
        assertThat(worksheet.factor()).isEqualByComparingTo("1.250000");
        assertThat(worksheet.computedPremium()).isEqualByComparingTo("15625.0000");
        assertThat(worksheet.currency()).isEqualTo("USD");
        assertThat(worksheet.status()).isEqualTo("CALCULATED");
        assertThat(worksheet.calcHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(worksheet.createdBy()).isEqualTo(new UUID(0L, 0L));
        assertThat(worksheet.createdAt()).isNotNull();

        Policy unchanged = policyService.get(TENANT, policy.id());
        assertThat(unchanged.premiumAmount()).isEqualByComparingTo("120000.0000");
        assertThat(unchanged.version()).isZero();
        assertThat(count("policy_rating_worksheet")).isEqualTo(1);
        assertThat(count("policy_version")).isEqualTo(1);
        assertThat(countEvents()).isZero();

        PolicyRatingWorksheet stored = worksheetRepository
                .findById(TENANT, policy.id(), worksheet.id()).orElseThrow();
        assertThat(stored.ratingMethod()).isEqualTo("RATE_TIMES_EXPOSURE");
        assertThat(stored.computedPremium()).isEqualByComparingTo("15625.0000");
        assertThat(stored.calcHash()).isEqualTo(worksheet.calcHash());
    }

    @Test
    void flatRatingDefaultsTheFactorAndAppliesBaseTimesFactor() {
        Policy policy = createPolicy("POL-RATE-0002");

        RatingResult result = ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("5000"), new BigDecimal("999999"),
                        null, true), null);

        PolicyRatingWorksheet worksheet = result.worksheet();
        assertThat(worksheet.ratingMethod()).isEqualTo("FLAT");
        assertThat(worksheet.exposureAmount()).isNull();
        assertThat(worksheet.factor()).isEqualByComparingTo("1.000000");
        assertThat(worksheet.computedPremium()).isEqualByComparingTo("5000.0000");

        assertThat(result.policy()).isNotNull();
        assertThat(result.policy().status()).isEqualTo("DRAFT");
        assertThat(result.policy().premiumAmount()).isEqualByComparingTo("5000.0000");
        assertThat(result.policy().version()).isEqualTo(1);
        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("5000.0000");
    }

    @Test
    void appliesRateTimesExposureAndEmitsTheRatedEvent() throws Exception {
        Policy policy = createPolicy("POL-RATE-0003");

        RatingResult result = ratingService.rate(TENANT, policy.id(),
                rtx("0.02", "500000", "1.5", true), null);

        PolicyRatingWorksheet worksheet = result.worksheet();
        assertThat(worksheet.computedPremium()).isEqualByComparingTo("15000.0000");
        assertThat(result.policy()).isNotNull();
        assertThat(result.policy().premiumAmount()).isEqualByComparingTo("15000.0000");
        assertThat(result.policy().version()).isEqualTo(1);
        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("15000.0000");
        assertThat(count("policy_version")).isEqualTo(1);
        assertThat(ratingService.latest(TENANT, policy.id()).id()).isEqualTo(worksheet.id());

        Map<String, Object> event = event(policy.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(2L);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(policy.id().toString());
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"policy\"")
                .contains("\"businessKey\":\"" + policy.policyNumber() + "\"");

        JsonNode payload = objectMapper.readTree((String) event.get("payload"));
        assertThat(payload.size()).isEqualTo(7);
        assertThat(payload.get("policyId").asText()).isEqualTo(policy.id().toString());
        assertThat(payload.get("policyNumber").asText()).isEqualTo(policy.policyNumber());
        assertThat(payload.get("premiumAmount").asText()).isEqualTo("15000.0000");
        assertThat(payload.get("currency").asText()).isEqualTo("USD");
        assertThat(payload.get("ratingMethod").asText()).isEqualTo("RATE_TIMES_EXPOSURE");
        assertThat(payload.get("calcHash").asText()).isEqualTo(worksheet.calcHash());
        assertThat(payload.get("worksheetId").asText()).isEqualTo(worksheet.id().toString());
    }

    @Test
    void quotedPoliciesAreRateableAndKeepTheirStatus() {
        Policy policy = createPolicy("POL-RATE-0004");
        Policy quoted = policyService.updateStatus(TENANT, policy.id(),
                new UpdatePolicyStatusRequest("QUOTED", null, null), null);
        assertThat(quoted.status()).isEqualTo("QUOTED");

        RatingResult result = ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("7500"), null, new BigDecimal("1.1"),
                        true), null);

        assertThat(result.worksheet().computedPremium()).isEqualByComparingTo("8250.0000");
        assertThat(result.policy().status()).isEqualTo("QUOTED");
        assertThat(result.policy().premiumAmount()).isEqualByComparingTo("8250.0000");
        assertThat(result.policy().version()).isEqualTo(quoted.version() + 1);
        assertThat(countEvents()).isEqualTo(1);
    }

    @Test
    void rejectsEveryOtherLifecycleStatusWithoutWriting() {
        Policy policy = createPolicy("POL-RATE-STATUS");
        for (String status : List.of("BOUND", "ISSUED", "IN_FORCE", "ENDORSED", "RENEWED",
                "CANCELLED", "EXPIRED", "NON_RENEWED", "REINSTATED", "VOID")) {
            jdbc.update("UPDATE policy SET status = ? WHERE tenant_id = ? AND id = ?",
                    status, TENANT, policy.id());

            assertThatThrownBy(() -> ratingService.rate(TENANT, policy.id(),
                    rtx("0.02", "500000", "1", false), null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo("POLICY_NOT_RATEABLE");
                        assertThat(api.meta()).containsEntry("sourceStatus", status);
                    });
        }

        assertThat(count("policy_rating_worksheet")).isZero();
        assertThat(countEvents()).isZero();
        Policy unchanged = policyService.get(TENANT, policy.id());
        assertThat(unchanged.premiumAmount()).isEqualByComparingTo("120000.0000");
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void rejectsInvalidRatingInputsWithoutWriting() {
        Policy policy = createPolicy("POL-RATE-VALIDATION");

        assertCode("RATING_METHOD_REQUIRED", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("  ", new BigDecimal("1"), null, null, false), null));
        assertCode("RATING_METHOD_INVALID", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("MORTALITY", new BigDecimal("1"), null, null, false), null));
        assertCode("RATING_BASE_RATE_REQUIRED", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", null, null, null, false), null));
        assertCode("RATING_BASE_RATE_INVALID", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("-1"), null, null, false), null));
        assertCode("RATING_EXPOSURE_REQUIRED", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"), null, null,
                        false), null));
        assertCode("RATING_EXPOSURE_INVALID", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"), BigDecimal.ZERO,
                        null, false), null));
        assertCode("RATING_FACTOR_INVALID", () -> ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("1"), null, new BigDecimal("-0.5"),
                        false), null));

        assertThat(count("policy_rating_worksheet")).isZero();
        assertThat(countEvents()).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isZero();
    }

    @Test
    void enforcesTheTenantWallOnRateAndRead() {
        Policy policy = createPolicy("POL-RATE-TENANT");

        assertCode(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", () -> ratingService.rate(
                OTHER_TENANT, policy.id(), rtx("0.02", "500000", "1", false), null));
        assertCode(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND",
                () -> ratingService.latest(OTHER_TENANT, policy.id()));
        assertCode(HttpStatus.NOT_FOUND, "RATING_WORKSHEET_NOT_FOUND",
                () -> ratingService.latest(TENANT, policy.id()));

        assertThat(count("policy_rating_worksheet")).isZero();
        assertThat(countEvents()).isZero();
    }

    @Test
    void rejectsAStaleIfMatchBeforeWriting() {
        Policy policy = createPolicy("POL-RATE-IF-MATCH");

        assertCode(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", () -> ratingService.rate(
                TENANT, policy.id(), rtx("0.02", "500000", "1", true), "\"99\""));

        assertThat(count("policy_rating_worksheet")).isZero();
        assertThat(countEvents()).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isZero();
    }

    @Test
    void latestReturnsTheNewestCalculation() {
        Policy policy = createPolicy("POL-RATE-LATEST");

        PolicyRatingWorksheet first = ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("1000"), null, null, false), null)
                .worksheet();
        // Move the first calculation strictly earlier so the newest-first order
        // does not depend on the wall clock's resolution in this test.
        jdbc.update("UPDATE policy_rating_worksheet SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(first.createdAt().minusSeconds(1)), first.id());

        PolicyRatingWorksheet second = ratingService.rate(TENANT, policy.id(),
                new RatingRequest("FLAT", new BigDecimal("1200"), null, null, false), null)
                .worksheet();

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(ratingService.latest(TENANT, policy.id()).id()).isEqualTo(second.id());
        assertThat(ratingService.latest(TENANT, policy.id()).computedPremium())
                .isEqualByComparingTo("1200.0000");
        assertThat(count("policy_rating_worksheet")).isEqualTo(2);
    }

    @Test
    void calcHashIsReproducibleForEquivalentInputsAcrossPolicies() {
        Policy firstPolicy = createPolicy("POL-RATE-HASH-1");
        Policy secondPolicy = createPolicy("POL-RATE-HASH-2");
        Policy thirdPolicy = createPolicy("POL-RATE-HASH-3");

        PolicyRatingWorksheet first = ratingService.rate(TENANT, firstPolicy.id(),
                rtx("100", "250000", "1", false), null).worksheet();
        PolicyRatingWorksheet second = ratingService.rate(TENANT, secondPolicy.id(),
                rtx("100.000000", "250000.0000", "1.000000", false), null).worksheet();
        PolicyRatingWorksheet third = ratingService.rate(TENANT, thirdPolicy.id(),
                rtx("101", "250000", "1", false), null).worksheet();

        assertThat(first.computedPremium()).isEqualByComparingTo("25000000.0000");
        assertThat(second.computedPremium()).isEqualByComparingTo("25000000.0000");
        assertThat(second.calcHash()).isEqualTo(first.calcHash());
        assertThat(third.calcHash()).isNotEqualTo(first.calcHash());
    }

    private Policy createPolicy(String policyNumber) {
        return policyService.create(new CreatePolicyRequest(TENANT, policyNumber, "CGL", PARTY,
                null, EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private static RatingRequest rtx(String baseRate, String exposure, String factor,
                                     boolean apply) {
        return new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal(baseRate),
                new BigDecimal(exposure), new BigDecimal(factor), apply);
    }

    private Map<String, Object> event(UUID policyId) {
        return jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, event_version, status,
                       tenant_id, payload, headers, partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 """, policyId, EVENT_TYPE);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class,
                EVENT_TYPE);
    }

    private static void assertCode(String code, Runnable call) {
        assertCode(HttpStatus.BAD_REQUEST, code, call);
    }

    private static void assertCode(HttpStatus status, String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(status);
                    assertThat(api.code()).isEqualTo(code);
                });
    }
}
