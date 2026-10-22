package com.eia.policy.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the Policy outbox contract against a real transaction manager: every
 * covered command writes its canonical event in the same transaction, and a
 * rollback leaves no event behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyOutboxIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Autowired
    private PolicyService policyService;

    @Autowired
    private EndorsementService endorsementService;

    @Autowired
    private CancellationService cancellationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of(
                "outbox_event", "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createWritesThePolicyAndCreatedEventTogether() {
        Policy policy = policyService.create(createRequest("POL-OUTBOX-0001"));

        Map<String, Object> event = event("policy.policy.created", policy.id());

        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(policy.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-OUTBOX-0001\"")
                .contains("\"productCode\":\"CGL\"")
                .contains("\"insuredPartyId\":\"" + PARTY + "\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"version\":0")
                .contains("\"effectiveDate\":\"2026-01-01\"")
                .contains("\"expirationDate\":\"2027-01-01\"")
                .contains("\"premiumAmount\":\"120000.0000\"")
                .contains("\"premiumCurrency\":\"USD\"")
                .contains("\"limitAmount\":\"5000000.0000\"")
                .contains("\"limitCurrency\":\"USD\"");

        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"policy\"")
                .contains("\"businessKey\":\"POL-OUTBOX-0001\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherThePolicyNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            policyService.create(createRequest("POL-OUTBOX-ROLLBACK"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusTransitionsWriteUpdatedAndVersionIssuedEvents() {
        Policy policy = createIssuedPolicy("POL-OUTBOX-0002");

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, policy.id());

        assertThat(events).extracting(event -> event.get("event_type")).containsExactly(
                "policy.policy.created",
                "policy.policy.updated",
                "policy.policy.updated",
                "policy.policy.updated",
                "policy.policy.version-issued");

        Map<String, Object> firstUpdate = events.get(1);
        assertThat(firstUpdate.get("aggregate_version")).isEqualTo(2L);
        assertThat((String) firstUpdate.get("payload"))
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"from\":\"DRAFT\"")
                .contains("\"to\":\"QUOTED\"")
                .contains("\"version\":1")
                .contains("\"policyNumber\":\"POL-OUTBOX-0002\"");

        Map<String, Object> issueUpdate = events.get(3);
        assertThat((String) issueUpdate.get("payload"))
                .contains("\"from\":\"BOUND\"")
                .contains("\"to\":\"ISSUED\"")
                .contains("\"status\":\"ISSUED\"")
                .contains("\"version\":3");

        Map<String, Object> versionIssued = events.get(4);
        assertThat(versionIssued.get("aggregate_version")).isEqualTo(4L);
        assertThat((String) versionIssued.get("payload"))
                .contains("\"status\":\"ISSUED\"")
                .contains("\"version\":3")
                .contains("\"versionNumber\":1")
                .contains("\"policyVersionNumber\":\"POL-OUTBOX-0002-01\"")
                .contains("\"changeType\":\"BIND\"")
                .contains("\"policyVersionStatus\":\"ISSUED\"")
                .contains("\"immutableHash\":\"");
        assertThat((String) versionIssued.get("payload")).matches(".*\"immutableHash\":\"[0-9a-f]{64}\".*");
    }

    @Test
    void endorsementWritesTheEndorsedEventAndItsVersionIssuedEvent() {
        Policy policy = createIssuedPolicy("POL-OUTBOX-0003");

        EndorsementResult result = endorsementService.create(TENANT, policy.id(),
                new CreateEndorsementRequest(null, "PREMIUM", LocalDate.of(2026, 6, 1),
                        "Premium endorsement for increased exposure",
                        new BigDecimal("5000.00"), "USD", "BROADEN",
                        List.of(new CreateEndorsementRequest.CoverageChange(
                                "GL_PRODUCTS", "ADD", "Products liability")),
                        null, null, null, null, null),
                null, null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.endorsement().endorsementNumber()).isEqualTo("END-0001");

        Map<String, Object> event = event("policy.policy.endorsed", policy.id());
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-OUTBOX-0003\"")
                .contains("\"status\":\"ISSUED\"")
                .contains("\"version\":4")
                .contains("\"endorsementId\":\"" + result.endorsement().id() + "\"")
                .contains("\"endorsementNumber\":\"END-0001\"")
                .contains("\"sequence\":1")
                .contains("\"endorsementType\":\"PREMIUM\"")
                .contains("\"endorsementEffectiveDate\":\"2026-06-01\"")
                .contains("\"premiumDeltaAmount\":\"5000.0000\"")
                .contains("\"premiumDeltaCurrency\":\"USD\"")
                .contains("\"coverageImpact\":\"BROADEN\"")
                .contains("\"coverageCode\":\"GL_PRODUCTS\"")
                .contains("\"changeType\":\"ADD\"")
                .contains("\"versionNumber\":2")
                .contains("\"policyVersionNumber\":\"POL-OUTBOX-0003-02\"");

        Map<String, Object> versionEvent =
                event("policy.policy.version-issued", policy.id(), "ENDORSEMENT");
        assertThat((String) versionEvent.get("payload"))
                .contains("\"changeType\":\"ENDORSEMENT\"")
                .contains("\"status\":\"ISSUED\"")
                .contains("\"versionNumber\":2")
                .contains("\"premiumDeltaAmount\":\"5000.0000\"");
    }

    @Test
    void cancellationWritesTheCancelledEventAndItsVersionIssuedEvent() {
        Policy policy = createIssuedPolicy("POL-OUTBOX-0004");

        CancellationResult result = cancellationService.cancel(TENANT, policy.id(),
                new CancelPolicyRequest("PRO_RATA", "Insured sold the business",
                        LocalDate.of(2026, 7, 2), 0, null, null),
                null, null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.policy().status()).isEqualTo("CANCELLED");

        Map<String, Object> event = event("policy.policy.cancelled", policy.id());
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-OUTBOX-0004\"")
                .contains("\"status\":\"CANCELLED\"")
                .contains("\"version\":4")
                .contains("\"cancellationId\":\"" + result.cancellation().id() + "\"")
                .contains("\"cancellationType\":\"PRO_RATA\"")
                .contains("\"reason\":\"Insured sold the business\"")
                .contains("\"cancellationEffectiveDate\":\"2026-07-02\"")
                .contains("\"termDays\":365")
                .contains("\"remainingDays\":183")
                .contains("\"proRataPct\":\"0.501370\"")
                .contains("\"earnedPremiumAmount\":\"59835.6000\"")
                .contains("\"returnPremiumAmount\":\"60164.4000\"")
                .contains("\"versionNumber\":2")
                .contains("\"policyVersionNumber\":\"POL-OUTBOX-0004-02\"");

        Map<String, Object> versionEvent =
                event("policy.policy.version-issued", policy.id(), "CANCELLATION");
        assertThat((String) versionEvent.get("payload"))
                .contains("\"changeType\":\"CANCELLATION\"")
                .contains("\"status\":\"CANCELLED\"")
                .contains("\"versionNumber\":2")
                .contains("\"premiumDeltaAmount\":\"-60164.4000\"");
    }

    @Test
    void cancellationRollbackKeepsThePolicyAndWritesNoCancelledEvent() {
        Policy policy = createIssuedPolicy("POL-OUTBOX-0005");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            cancellationService.cancel(TENANT, policy.id(),
                    new CancelPolicyRequest("FLAT", "Withdrawn by insured",
                            LocalDate.of(2026, 7, 2), 0, null, null),
                    null, null);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(policyService.get(TENANT, policy.id()).status()).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM policy_cancellation
                 WHERE tenant_id = ? AND policy_id = ?
                """, Integer.class, TENANT, policy.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'policy.policy.cancelled'
                """, Integer.class, policy.id())).isZero();
    }

    @Test
    void reinstatementWritesTheReinstatedEventAndItsVersionIssuedEvent() {
        Policy policy = createIssuedPolicy("POL-OUTBOX-0006");
        CancellationResult cancellation = cancellationService.cancel(TENANT, policy.id(),
                new CancelPolicyRequest("PRO_RATA", "Insured sold the business",
                        LocalDate.of(2026, 7, 2), 0, null, null),
                null, null);

        ReinstatementResult result = cancellationService.reinstate(TENANT, policy.id(),
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received in full",
                        APPROVER, APPROVAL, "RCPT-2026-0001", new BigDecimal("1500.00"), "USD"),
                null, null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.policy().status()).isEqualTo("REINSTATED");

        Map<String, Object> event = event("policy.policy.reinstated", policy.id());
        assertThat(event.get("aggregate_version")).isEqualTo(6L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-OUTBOX-0006\"")
                .contains("\"status\":\"REINSTATED\"")
                .contains("\"version\":5")
                .contains("\"reinstatementId\":\"" + result.reinstatement().id() + "\"")
                .contains("\"cancellationId\":\"" + cancellation.cancellation().id() + "\"")
                .contains("\"reinstatementEffectiveDate\":\"2026-08-01\"")
                .contains("\"reason\":\"Premium received in full\"")
                .contains("\"approvalRef\":\"" + APPROVAL + "\"")
                .contains("\"approvedBy\":\"" + APPROVER + "\"")
                .contains("\"premiumEvidenceRef\":\"RCPT-2026-0001\"")
                .contains("\"premiumEvidenceAmount\":\"1500.0000\"")
                .contains("\"premiumEvidenceCurrency\":\"USD\"")
                .contains("\"versionNumber\":3")
                .contains("\"policyVersionNumber\":\"POL-OUTBOX-0006-03\"");

        Map<String, Object> versionEvent =
                event("policy.policy.version-issued", policy.id(), "REINSTATEMENT");
        assertThat((String) versionEvent.get("payload"))
                .contains("\"changeType\":\"REINSTATEMENT\"")
                .contains("\"status\":\"REINSTATED\"")
                .contains("\"versionNumber\":3");
    }

    private Policy createIssuedPolicy(String policyNumber) {
        Policy policy = policyService.create(createRequest(policyNumber));
        addNamedInsured(policy.id());
        transition(policy.id(), "QUOTED");
        transition(policy.id(), "BOUND");
        return transition(policy.id(), "ISSUED");
    }

    private Policy transition(UUID policyId, String target) {
        return policyService.updateStatus(TENANT, policyId,
                new UpdatePolicyStatusRequest(target, null, null), null);
    }

    private void addNamedInsured(UUID policyId) {
        jdbc.update("""
                INSERT INTO policy_insured
                       (id, tenant_id, policy_id, party_id, insured_role, display_name,
                        certificate_number, scope, effective_from, effective_to, status, created_at)
                VALUES (?, ?, ?, ?, 'NAMED_INSURED', 'Acme Chemical Corporation',
                        NULL, NULL, ?, ?, 'ACTIVE', ?)
                """, UUID.randomUUID(), TENANT, policyId, PARTY, EFFECTIVE, EXPIRATION,
                Timestamp.from(Instant.now()));
    }

    private Map<String, Object> event(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, event_version, status,
                       tenant_id, payload, headers, partition_key, occurred_at, recorded_at,
                       next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, eventType);
    }

    private Map<String, Object> event(String eventType, UUID policyId, String changeType) {
        return jdbc.queryForMap("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                   AND payload LIKE ?
                """, policyId, eventType, "%\"changeType\":\"" + changeType + "\"%");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static CreatePolicyRequest createRequest(String policyNumber) {
        return new CreatePolicyRequest(TENANT, policyNumber, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD");
    }
}
