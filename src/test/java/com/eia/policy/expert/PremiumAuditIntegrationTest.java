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
 * End-to-end premium audit slice against H2: increases and decreases, the
 * no-change guard, lifecycle and tenant walls, version timeline resolution,
 * version/hash integrity, and the outbox contract.
 */
@SpringBootTest
@ActiveProfiles("test")
class PremiumAuditIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate AUDIT_DATE = LocalDate.of(2026, 6, 1);
    private static final String REASON = "Annual exposure audit";

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PremiumAuditService auditService;

    @Autowired
    private PolicyVersionService versionService;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private EndorsementRepository endorsementRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "outbox_event", "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void increasesThePremiumAndIssuesTheAuditVersionEndorsementAndEvent() throws Exception {
        Policy policy = inForcePolicy("POL-AUDIT-0001");
        PolicyVersion initialVersion = versionRepository.findLatest(TENANT, policy.id()).orElseThrow();

        Policy audited = auditService.audit(TENANT, policy.id(), request("150000.00", AUDIT_DATE),
                null);

        assertThat(audited.id()).isEqualTo(policy.id());
        assertThat(audited.status()).isEqualTo("IN_FORCE");
        assertThat(audited.premiumAmount()).isEqualByComparingTo("150000.0000");
        assertThat(audited.version()).isEqualTo(policy.version() + 1);
        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("150000.0000");

        List<PolicyEndorsement> endorsements = endorsementRepository.findAll(TENANT, policy.id());
        assertThat(endorsements).hasSize(1);
        PolicyEndorsement endorsement = endorsements.getFirst();
        assertThat(endorsement.endorsementType()).isEqualTo("PREMIUM_AUDIT");
        assertThat(endorsement.effectiveDate()).isEqualTo(AUDIT_DATE);
        assertThat(endorsement.premiumDeltaAmount()).isEqualByComparingTo("30000.0000");
        assertThat(endorsement.premiumDeltaCurrency()).isEqualTo("USD");
        assertThat(endorsement.description())
                .isEqualTo("Premium audit: 120000.0000 -> 150000.0000 (30000.0000)");
        assertThat(endorsement.coverageImpact()).isEqualTo("NONE");
        assertThat(endorsement.backdateReason()).isNull();
        assertThat(endorsement.status()).isEqualTo("ISSUED");

        List<PolicyVersion> versions = versionRepository.findAll(TENANT, policy.id());
        assertThat(versions).hasSize(2);
        PolicyVersion version = versions.getFirst();
        assertThat(version.changeType()).isEqualTo("PREMIUM_AUDIT");
        assertThat(version.status()).isEqualTo("ISSUED");
        assertThat(version.effectiveFrom()).isEqualTo(AUDIT_DATE);
        assertThat(version.premiumDeltaAmount()).isEqualByComparingTo("30000.0000");
        assertThat(version.premiumDeltaCurrency()).isEqualTo("USD");
        assertThat(version.description())
                .isEqualTo("Premium audit: 120000.0000 -> 150000.0000 (30000.0000)");
        assertThat(version.supersedesVersionId()).isEqualTo(initialVersion.id());
        assertThat(version.immutableHash()).hasSize(64);

        CurrentVersion current = versionService.current(TENANT, policy.id());
        assertThat(current.current()).isTrue();
        assertThat(current.hashVerified()).isTrue();
        assertThat(current.version().id()).isEqualTo(version.id());

        Map<String, Object> event = event("policy.policy.premium-audited", policy.id());
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
        assertThat(payload.size()).isEqualTo(8);
        assertThat(payload.get("policyId").asText()).isEqualTo(policy.id().toString());
        assertThat(payload.get("policyNumber").asText()).isEqualTo(policy.policyNumber());
        assertThat(payload.get("previousPremium").asText()).isEqualTo("120000.0000");
        assertThat(payload.get("auditedPremium").asText()).isEqualTo("150000.0000");
        assertThat(payload.get("delta").asText()).isEqualTo("30000.0000");
        assertThat(payload.get("currency").asText()).isEqualTo("USD");
        assertThat(payload.get("effectiveDate").asText()).isEqualTo("2026-06-01");
        assertThat(payload.get("reason").asText()).isEqualTo(REASON);

        Map<String, Object> versionEvent = event("policy.policy.version-issued", policy.id());
        assertThat((String) versionEvent.get("payload"))
                .contains("\"changeType\":\"PREMIUM_AUDIT\"")
                .contains("\"versionNumber\":2")
                .contains("\"premiumDeltaAmount\":\"30000.0000\"");
    }

    @Test
    void decreasesThePremiumWithANegativeDelta() throws Exception {
        Policy policy = policy("POL-AUDIT-0002");
        jdbc.update("UPDATE policy SET status = 'ENDORSED' WHERE tenant_id = ? AND id = ?",
                TENANT, policy.id());

        Policy audited = auditService.audit(TENANT, policy.id(), request("90000.00", AUDIT_DATE),
                null);

        assertThat(audited.premiumAmount()).isEqualByComparingTo("90000.0000");
        PolicyEndorsement endorsement = endorsementRepository.findAll(TENANT, policy.id()).getFirst();
        assertThat(endorsement.premiumDeltaAmount()).isEqualByComparingTo("-30000.0000");
        assertThat(endorsement.description())
                .isEqualTo("Premium audit: 120000.0000 -> 90000.0000 (-30000.0000)");

        PolicyVersion version = versionRepository.findAll(TENANT, policy.id()).getFirst();
        assertThat(version.changeType()).isEqualTo("PREMIUM_AUDIT");
        assertThat(version.premiumDeltaAmount()).isEqualByComparingTo("-30000.0000");

        JsonNode payload = objectMapper.readTree(
                (String) event("policy.policy.premium-audited", policy.id()).get("payload"));
        assertThat(payload.get("auditedPremium").asText()).isEqualTo("90000.0000");
        assertThat(payload.get("delta").asText()).isEqualTo("-30000.0000");
    }

    @Test
    void rejectsANoOpAuditWithoutWritingAnything() {
        Policy policy = inForcePolicy("POL-AUDIT-0003");
        int policyVersion = policy.version();
        int endorsementsBefore = count("policy_endorsement");
        int versionsBefore = count("policy_version");
        int eventsBefore = countEvents("policy.policy.premium-audited");

        assertThatThrownBy(() -> auditService.audit(TENANT, policy.id(),
                new PremiumAuditRequest(new BigDecimal("120000"), AUDIT_DATE, REASON, null, null),
                null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_CHANGE");
                });

        Policy unchanged = policyService.get(TENANT, policy.id());
        assertThat(unchanged.premiumAmount()).isEqualByComparingTo("120000.0000");
        assertThat(unchanged.version()).isEqualTo(policyVersion);
        assertThat(count("policy_endorsement")).isEqualTo(endorsementsBefore);
        assertThat(count("policy_version")).isEqualTo(versionsBefore);
        assertThat(countEvents("policy.policy.premium-audited")).isEqualTo(eventsBefore);
    }

    @Test
    void acceptsEveryAuditableStatusAndPreservesIt() {
        // A BOUND policy still has the initial DRAFT version; later states have
        // an ISSUED version. The audit is valid for both.
        for (String status : List.of("BOUND", "ISSUED", "IN_FORCE", "ENDORSED", "REINSTATED")) {
            Policy policy = policy("POL-AUDIT-STATUS-" + status);
            jdbc.update("UPDATE policy SET status = ? WHERE tenant_id = ? AND id = ?",
                    status, TENANT, policy.id());

            Policy audited = auditService.audit(TENANT, policy.id(), request("150000.00",
                    AUDIT_DATE), null);

            assertThat(audited.status()).isEqualTo(status);
            assertThat(audited.premiumAmount()).isEqualByComparingTo("150000.0000");
            assertThat(audited.version()).isEqualTo(1);
        }
    }

    @Test
    void aBoundAuditDoesNotBlockTheLaterIssueTransition() {
        Policy policy = policy("POL-AUDIT-BOUND-ISSUE");
        jdbc.update("UPDATE policy SET status = 'BOUND' WHERE tenant_id = ? AND id = ?",
                TENANT, policy.id());
        addNamedInsured(policy.id());
        auditService.audit(TENANT, policy.id(), request("150000.00", AUDIT_DATE), null);

        Policy issued = policyService.updateStatus(TENANT, policy.id(),
                new UpdatePolicyStatusRequest("ISSUED", null, null), null);

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.premiumAmount()).isEqualByComparingTo("150000.0000");
        List<PolicyVersion> versions = versionRepository.findAll(TENANT, policy.id());
        assertThat(versions).hasSize(3);
        assertThat(versions.getFirst().changeType()).isEqualTo("ISSUE");
        assertThat(versions.getFirst().status()).isEqualTo("ISSUED");
        assertThat(versions).filteredOn(version -> "PREMIUM_AUDIT".equals(version.changeType()))
                .singleElement()
                .satisfies(version -> assertThat(version.status()).isEqualTo("SUPERSEDED"));
    }

    @Test
    void rejectsEveryIneligibleStatusWithoutWriting() {
        Policy policy = policy("POL-AUDIT-BLOCKED");
        for (String status : List.of("DRAFT", "QUOTED", "RENEWED", "CANCELLED", "EXPIRED",
                "NON_RENEWED", "VOID")) {
            jdbc.update("UPDATE policy SET status = ? WHERE tenant_id = ? AND id = ?",
                    status, TENANT, policy.id());

            assertThatThrownBy(() -> auditService.audit(TENANT, policy.id(),
                    request("150000.00", AUDIT_DATE), null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo("PREMIUM_AUDIT_NOT_ALLOWED");
                        assertThat(api.meta()).containsEntry("sourceStatus", status);
                    });
        }

        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("120000.0000");
        assertThat(count("policy_endorsement")).isZero();
        assertThat(count("policy_version")).isEqualTo(1);
        assertThat(countEvents("policy.policy.premium-audited")).isZero();
    }

    @Test
    void enforcesTheTenantWall() {
        Policy policy = inForcePolicy("POL-AUDIT-TENANT");
        int versionsBefore = count("policy_version");

        assertThatThrownBy(() -> auditService.audit(OTHER_TENANT, policy.id(),
                request("150000.00", AUDIT_DATE), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("POLICY_NOT_FOUND");
                });

        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("120000.0000");
        assertThat(count("policy_version")).isEqualTo(versionsBefore);
        assertThat(countEvents("policy.policy.premium-audited")).isZero();
    }

    @Test
    void resolvesTheEffectiveDateThroughTheVersionTimeline() {
        Policy policy = inForcePolicy("POL-AUDIT-DATE");

        assertThatThrownBy(() -> auditService.audit(TENANT, policy.id(),
                request("150000.00", EFFECTIVE.minusDays(1)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });

        assertThatThrownBy(() -> auditService.audit(TENANT, policy.id(),
                request("150000.00", EXPIRATION), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("VERSION_OUTSIDE_POLICY_TERM"));

        auditService.audit(TENANT, policy.id(), request("150000.00", AUDIT_DATE), null);

        assertThatThrownBy(() -> auditService.audit(TENANT, policy.id(),
                request("140000.00", LocalDate.of(2026, 3, 1)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("VERSION_REGRESSION");
                });

        assertThat(policyService.get(TENANT, policy.id()).premiumAmount())
                .isEqualByComparingTo("150000.0000");
    }

    @Test
    void anApprovedBackdateIsClampedToPolicyInceptionOnTheVersion() {
        Policy policy = inForcePolicy("POL-AUDIT-BACKDATE");

        auditService.audit(TENANT, policy.id(), new PremiumAuditRequest(
                new BigDecimal("150000.00"), EFFECTIVE.minusDays(10), "Retroactive audit",
                APPROVER, APPROVAL), null);

        PolicyEndorsement endorsement = endorsementRepository.findAll(TENANT, policy.id()).getFirst();
        assertThat(endorsement.effectiveDate()).isEqualTo(EFFECTIVE.minusDays(10));
        assertThat(endorsement.backdateReason()).isEqualTo("Retroactive audit");
        assertThat(endorsement.approvedBy()).isEqualTo(APPROVER);
        assertThat(endorsement.approvalRef()).isEqualTo(APPROVAL);

        PolicyVersion version = versionRepository.findAll(TENANT, policy.id()).getFirst();
        assertThat(version.effectiveFrom()).isEqualTo(EFFECTIVE);
        assertThat(version.approvedBy()).isEqualTo(APPROVER);
        assertThat(version.changeType()).isEqualTo("PREMIUM_AUDIT");
    }

    private Policy inForcePolicy(String policyNumber) {
        Policy policy = policy(policyNumber);
        jdbc.update("UPDATE policy SET status = 'IN_FORCE' WHERE tenant_id = ? AND id = ?",
                TENANT, policy.id());
        return policyService.get(TENANT, policy.id());
    }

    private void addNamedInsured(UUID policyId) {
        jdbc.update("""
                INSERT INTO policy_insured
                       (id, tenant_id, policy_id, party_id, insured_role, display_name,
                        certificate_number, scope, effective_from, effective_to, status, created_at)
                VALUES (?, ?, ?, ?, 'NAMED_INSURED', 'Acme Chemical Corporation',
                        NULL, NULL, ?, ?, 'ACTIVE', ?)
                """, UUID.randomUUID(), TENANT, policyId, PARTY, EFFECTIVE, EXPIRATION,
                java.sql.Timestamp.from(java.time.Instant.now()));
    }

    private Policy policy(String policyNumber) {
        return policyService.create(new CreatePolicyRequest(TENANT, policyNumber, "CGL", PARTY,
                null, EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private static PremiumAuditRequest request(String auditedPremium, LocalDate effectiveDate) {
        return new PremiumAuditRequest(new BigDecimal(auditedPremium), effectiveDate, REASON,
                null, null);
    }

    private Map<String, Object> event(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, event_version, status,
                       tenant_id, payload, headers, partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }
}
