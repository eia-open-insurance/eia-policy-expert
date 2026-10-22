package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end renewal slice against H2: eligible source states, inherited
 * defaults, coverage/limit/insured/location copying, the predecessor link, the
 * tenant wall, the outbox event, and transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyRenewalIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate RENEWED_EFFECTIVE = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENEWED_EXPIRATION = LocalDate.of(2028, 1, 1);

    @Autowired
    private PolicyRenewalService renewalService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private CoverageService coverageService;

    @Autowired
    private PolicyPartyService partyService;

    @Autowired
    private CoverageRepository coverageRepository;

    @Autowired
    private PolicyPartyRepository partyRepository;

    @Autowired
    private OutboxRepository outboxRepository;

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
    void renewsAnIssuedPolicyAndCopiesItsTermsAndChildren() {
        Policy source = createPolicy("POL-RENEW-0001");
        addCoverage(source);
        addLocation(source);
        addNamedInsured(source);
        addAdditionalInsured(source);
        Policy issued = issue(source);
        int sourceVersion = issued.version();

        Policy renewal = renewalService.renew(TENANT, issued.id(),
                new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null));

        assertThat(renewal.id()).isNotEqualTo(issued.id());
        assertThat(renewal.status()).isEqualTo("DRAFT");
        assertThat(renewal.policyNumber()).isNotEqualTo(issued.policyNumber());
        assertThat(renewal.policyNumber()).startsWith("POL-");
        assertThat(renewal.productCode()).isEqualTo(issued.productCode());
        assertThat(renewal.insuredPartyId()).isEqualTo(issued.insuredPartyId());
        assertThat(renewal.renewedFromPolicyId()).isEqualTo(issued.id());
        assertThat(renewal.effectiveDate()).isEqualTo(RENEWED_EFFECTIVE);
        assertThat(renewal.expirationDate()).isEqualTo(RENEWED_EXPIRATION);
        assertThat(renewal.premiumAmount()).isEqualByComparingTo("120000.0000");
        assertThat(renewal.premiumCurrency()).isEqualTo("USD");
        assertThat(renewal.limitAmount()).isEqualByComparingTo("5000000.0000");
        assertThat(renewal.limitCurrency()).isEqualTo("USD");
        assertThat(renewal.version()).isZero();

        Policy unchanged = policyService.get(TENANT, issued.id());
        assertThat(unchanged.status()).isEqualTo("ISSUED");
        assertThat(unchanged.version()).isEqualTo(sourceVersion);
        assertThat(unchanged.renewedFromPolicyId()).isNull();
        assertThat(count("policy")).isEqualTo(2);

        List<PolicyCoverage> coverages = coverageRepository.findCoverages(TENANT, renewal.id());
        assertThat(coverages).hasSize(1);
        PolicyCoverage coverage = coverages.getFirst();
        assertThat(coverage.coverageCode()).isEqualTo("GL_PRODUCTS");
        assertThat(coverage.coverageName()).isEqualTo("Products liability");
        assertThat(coverage.coveragePart()).isEqualTo("A");
        assertThat(coverage.occurrenceBasis()).isEqualTo("OCCURRENCE");
        assertThat(coverage.coinsurancePct()).isEqualByComparingTo("0.100000");
        assertThat(coverage.effectiveFrom()).isEqualTo(RENEWED_EFFECTIVE);
        assertThat(coverage.effectiveTo()).isEqualTo(RENEWED_EXPIRATION);

        PolicyLimit limit = coverageRepository
                .findLimitByCoverage(TENANT, renewal.id(), coverage.id()).orElseThrow();
        assertThat(limit.perOccurrenceAmount()).isEqualByComparingTo("1000000.0000");
        assertThat(limit.aggregateAmount()).isEqualByComparingTo("2000000.0000");
        assertThat(limit.aggregateBasis()).isEqualTo("PER_POLICY");
        assertThat(limit.sublimitAmount()).isEqualByComparingTo("500000.0000");
        assertThat(limit.deductibleAmount()).isEqualByComparingTo("10000.0000");
        assertThat(limit.coinsurancePct()).isEqualByComparingTo("0.100000");
        assertThat(limit.currency()).isEqualTo("USD");
        assertThat(limit.erosionBasis()).isEqualTo("PAID_PLUS_RESERVED");
        assertThat(limit.effectiveFrom()).isEqualTo(RENEWED_EFFECTIVE);
        assertThat(limit.effectiveTo()).isEqualTo(RENEWED_EXPIRATION);

        PolicyLimitState state = coverageRepository
                .findState(TENANT, renewal.id(), limit.id()).orElseThrow();
        assertThat(state.originalAmount()).isEqualByComparingTo("2000000.0000");
        assertThat(state.remainingAmount()).isEqualByComparingTo("2000000.0000");
        assertThat(state.paidAmount()).isEqualByComparingTo("0.0000");
        assertThat(state.incurredAmount()).isEqualByComparingTo("0.0000");

        List<PolicyInsured> insureds = partyRepository.findInsureds(TENANT, renewal.id());
        assertThat(insureds).extracting(PolicyInsured::insuredRole)
                .containsExactlyInAnyOrder("NAMED_INSURED", "ADDITIONAL_INSURED");
        assertThat(insureds).allSatisfy(insured -> {
            assertThat(insured.partyId()).isEqualTo(PARTY);
            assertThat(insured.effectiveFrom()).isEqualTo(RENEWED_EFFECTIVE);
            assertThat(insured.effectiveTo()).isEqualTo(RENEWED_EXPIRATION);
        });

        List<PolicyLocation> locations = partyRepository.findLocations(TENANT, renewal.id());
        assertThat(locations).hasSize(1);
        assertThat(locations.getFirst().locationCode()).isEqualTo("LOC-1");
        assertThat(locations.getFirst().city()).isEqualTo("Columbus");
        assertThat(locations.getFirst().effectiveFrom()).isEqualTo(RENEWED_EFFECTIVE);
        assertThat(locations.getFirst().effectiveTo()).isEqualTo(RENEWED_EXPIRATION);

        Map<String, Object> event = event("policy.policy.renewed", renewal.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"sourcePolicyId\":\"" + issued.id() + "\"")
                .contains("\"sourcePolicyNumber\":\"" + issued.policyNumber() + "\"")
                .contains("\"newPolicyId\":\"" + renewal.id() + "\"")
                .contains("\"newPolicyNumber\":\"" + renewal.policyNumber() + "\"")
                .contains("\"renewedFromPolicyId\":\"" + issued.id() + "\"")
                .contains("\"effectiveDate\":\"2027-01-02\"")
                .contains("\"expirationDate\":\"2028-01-01\"")
                .contains("\"premiumAmount\":\"120000.0000\"")
                .contains("\"premiumCurrency\":\"USD\"")
                .contains("\"limitAmount\":\"5000000.0000\"")
                .contains("\"limitCurrency\":\"USD\"");
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"policy\"")
                .contains("\"businessKey\":\"" + renewal.policyNumber() + "\"");

        EventEnvelope envelope = outboxRepository
                .findById((UUID) event.get("id")).orElseThrow().toEnvelope();
        assertThat(envelope.eventType()).isEqualTo("policy.policy.renewed");
        assertThat(envelope.book()).isEqualTo(Book.DIRECT);
        assertThat(envelope.aggregateId()).isEqualTo(renewal.id());
        assertThat(envelope.aggregateVersion()).isEqualTo(1L);
    }

    @Test
    void acceptsEveryRenewableSourceStatus() {
        for (String status : List.of("ISSUED", "IN_FORCE", "ENDORSED", "REINSTATED")) {
            Policy source = createPolicy("POL-RENEW-STATUS-" + status);
            jdbc.update("UPDATE policy SET status = ? WHERE tenant_id = ? AND id = ?",
                    status, TENANT, source.id());

            Policy renewal = renewalService.renew(TENANT, source.id(),
                    new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null));

            assertThat(renewal.status()).isEqualTo("DRAFT");
            assertThat(renewal.renewedFromPolicyId()).isEqualTo(source.id());
            assertThat(policyService.get(TENANT, source.id()).status()).isEqualTo(status);
        }
    }

    @Test
    void rejectsEveryBlockedSourceStatusWithoutWriting() {
        Policy source = createPolicy("POL-RENEW-BLOCKED");
        int policiesBefore = count("policy");

        for (String status : List.of("DRAFT", "QUOTED", "BOUND", "RENEWED", "CANCELLED",
                "EXPIRED", "NON_RENEWED", "VOID")) {
            jdbc.update("UPDATE policy SET status = ? WHERE tenant_id = ? AND id = ?",
                    status, TENANT, source.id());

            assertThatThrownBy(() -> renewalService.renew(TENANT, source.id(),
                    new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo("RENEWAL_NOT_ALLOWED");
                        assertThat(api.meta()).containsEntry("sourceStatus", status);
                    });
        }

        assertThat(count("policy")).isEqualTo(policiesBefore);
        assertThat(countEvents("policy.policy.renewed")).isZero();
    }

    @Test
    void inheritsDefaultsAndHonoursExplicitOverrides() {
        Policy source = issuedPolicy("POL-RENEW-DEFAULTS");

        Policy inherited = renewalService.renew(TENANT, source.id(),
                new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null));
        assertThat(inherited.effectiveDate()).isEqualTo(RENEWED_EFFECTIVE);
        assertThat(inherited.premiumAmount()).isEqualByComparingTo("120000.0000");
        assertThat(inherited.premiumCurrency()).isEqualTo("USD");
        assertThat(inherited.limitAmount()).isEqualByComparingTo("5000000.0000");
        assertThat(inherited.limitCurrency()).isEqualTo("USD");

        Policy overridden = renewalService.renew(TENANT, source.id(),
                new RenewPolicyRequest(EXPIRATION, EXPIRATION.plusYears(2),
                        new BigDecimal("99000.00"), "EUR",
                        new BigDecimal("3000000.00"), "EUR"));
        assertThat(overridden.effectiveDate()).isEqualTo(EXPIRATION);
        assertThat(overridden.expirationDate()).isEqualTo(EXPIRATION.plusYears(2));
        assertThat(overridden.premiumAmount()).isEqualByComparingTo("99000.0000");
        assertThat(overridden.premiumCurrency()).isEqualTo("EUR");
        assertThat(overridden.limitAmount()).isEqualByComparingTo("3000000.0000");
        assertThat(overridden.limitCurrency()).isEqualTo("EUR");
    }

    @Test
    void enforcesTheTenantWall() {
        Policy source = issuedPolicy("POL-RENEW-TENANT");
        int policiesBefore = count("policy");

        assertThatThrownBy(() -> renewalService.renew(OTHER_TENANT, source.id(),
                new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("POLICY_NOT_FOUND");
                });

        assertThat(count("policy")).isEqualTo(policiesBefore);
        assertThat(countEvents("policy.policy.renewed")).isZero();
    }

    @Test
    void rollbackLeavesNoRenewalPolicyChildrenVersionOrEvent() {
        Policy source = createPolicy("POL-RENEW-ROLLBACK");
        addCoverage(source);
        addNamedInsured(source);
        Policy issued = issue(source);
        int policiesBefore = count("policy");
        int coveragesBefore = count("policy_coverage");
        int versionsBefore = count("policy_version");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            renewalService.renew(TENANT, issued.id(),
                    new RenewPolicyRequest(null, RENEWED_EXPIRATION, null, null, null, null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy")).isEqualTo(policiesBefore);
        assertThat(count("policy_coverage")).isEqualTo(coveragesBefore);
        assertThat(count("policy_version")).isEqualTo(versionsBefore);
        assertThat(countEvents("policy.policy.renewed")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM policy WHERE tenant_id = ? AND renewed_from_policy_id = ?
                """, Integer.class, TENANT, issued.id())).isZero();
    }

    private Policy createPolicy(String number) {
        return policyService.create(new CreatePolicyRequest(TENANT, number, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private Policy issuedPolicy(String number) {
        Policy policy = createPolicy(number);
        addNamedInsured(policy);
        return issue(policy);
    }

    private Policy issue(Policy policy) {
        transition(policy, "QUOTED");
        transition(policy, "BOUND");
        return transition(policy, "ISSUED");
    }

    private Policy transition(Policy policy, String target) {
        return policyService.updateStatus(TENANT, policy.id(),
                new UpdatePolicyStatusRequest(target, null, null), null);
    }

    private void addNamedInsured(Policy policy) {
        partyService.createInsured(TENANT, policy.id(), new CreateInsuredRequest(PARTY,
                "NAMED_INSURED", "Acme Chemical Corporation", null, null,
                EFFECTIVE, EXPIRATION), null, null);
    }

    private void addAdditionalInsured(Policy policy) {
        partyService.createInsured(TENANT, policy.id(), new CreateInsuredRequest(PARTY,
                "ADDITIONAL_INSURED", "Acme Chemical Corporation", "CERT-1", "Global",
                EFFECTIVE, EXPIRATION), null, null);
    }

    private void addCoverage(Policy policy) {
        coverageService.create(TENANT, policy.id(), new CreateCoverageRequest(
                "GL_PRODUCTS", "Products liability", "A", "OCCURRENCE", EFFECTIVE, EXPIRATION,
                new BigDecimal("0.10"), new CreateCoverageRequest.LimitSpec(
                new BigDecimal("1000000.00"), new BigDecimal("2000000.00"), "PER_POLICY",
                new BigDecimal("500000.00"), new BigDecimal("10000.00"),
                new BigDecimal("0.10"), "USD", "PAID_PLUS_RESERVED")), null, null);
    }

    private void addLocation(Policy policy) {
        partyService.createLocation(TENANT, policy.id(), new CreateLocationRequest(
                "LOC-1", "1 Main Street", null, "Columbus", "US-OH", "43215", "USA",
                EFFECTIVE, EXPIRATION), null, null);
    }

    private Map<String, Object> event(String eventType, UUID aggregateId) {
        return jdbc.queryForMap("""
                SELECT id, event_type, aggregate_type, aggregate_version, event_version, status,
                       tenant_id, payload, headers, partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, aggregateId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }
}
