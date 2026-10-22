package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that issuance heals a policy timeline left with more than one open
 * DRAFT/ISSUED version: every stale row is superseded before the overlap check,
 * and a rejected overlap rolls those closures back with the transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class VersionIssuerIntegrationTest {
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate CUTOVER = LocalDate.of(2026, 6, 1);

    @Autowired
    private VersionIssuer versionIssuer;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Test
    void issueClosesEveryOpenVersionAndSupersedesTheLatest() {
        UUID tenantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = policy(tenantId, policyId);
        PolicyVersion first = version(tenantId, policyId, 1, "ISSUED", EFFECTIVE, null);
        PolicyVersion draft = version(tenantId, policyId, 2, "DRAFT",
                LocalDate.of(2026, 3, 1), null);
        PolicyVersion latest = version(tenantId, policyId, 3, "ISSUED",
                LocalDate.of(2026, 5, 1), null);
        versionRepository.insert(first);
        versionRepository.insert(draft);
        versionRepository.insert(latest);

        UUID newVersionId = UUID.randomUUID();
        PolicyVersion issued = versionIssuer.issue(policy, newVersionId, "ENDORSEMENT", CUTOVER,
                "Increase the general aggregate", new BigDecimal("5000.00"), "USD", null);

        assertThat(issued.id()).isEqualTo(newVersionId);
        assertThat(issued.versionNumber()).isEqualTo(4);
        assertThat(issued.policyVersionNumber()).isEqualTo(policy.policyNumber() + "-04");
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.effectiveFrom()).isEqualTo(CUTOVER);
        assertThat(issued.effectiveTo()).isNull();
        assertThat(issued.supersedesVersionId()).isEqualTo(latest.id());
        assertThat(issued.immutableHash()).hasSize(64);

        List<PolicyVersion> all = versionRepository.findAll(tenantId, policyId);
        assertThat(all).hasSize(4);
        assertThat(all).filteredOn(version -> !version.id().equals(newVersionId))
                .allSatisfy(version -> {
                    assertThat(version.status()).isEqualTo("SUPERSEDED");
                    assertThat(version.effectiveTo()).isEqualTo(CUTOVER);
                });
        assertThat(all)
                .filteredOn(version -> "DRAFT".equals(version.status())
                        || "ISSUED".equals(version.status()))
                .extracting(PolicyVersion::id)
                .containsExactly(newVersionId);
    }

    @Test
    void rejectedOverlapRollsBackEveryClosure() {
        UUID tenantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = policy(tenantId, policyId);
        // The voided window remains outside supersedeAllOpen's scope, so the
        // overlap check still rejects after the open rows have been closed.
        PolicyVersion voided = version(tenantId, policyId, 1, "VOIDED", EFFECTIVE, EXPIRATION);
        PolicyVersion issued = version(tenantId, policyId, 2, "ISSUED", EFFECTIVE, null);
        PolicyVersion draft = version(tenantId, policyId, 3, "DRAFT",
                LocalDate.of(2026, 3, 1), null);
        versionRepository.insert(voided);
        versionRepository.insert(issued);
        versionRepository.insert(draft);

        assertThatThrownBy(() -> versionIssuer.issue(policy, UUID.randomUUID(), "ENDORSEMENT",
                CUTOVER, "Increase the general aggregate", new BigDecimal("5000.00"), "USD", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("VERSION_RANGE_OVERLAP");
                });

        List<PolicyVersion> all = versionRepository.findAll(tenantId, policyId);
        assertThat(all).hasSize(3);
        assertThat(versionRepository.findById(tenantId, policyId, issued.id()).orElseThrow())
                .satisfies(version -> {
                    assertThat(version.status()).isEqualTo("ISSUED");
                    assertThat(version.effectiveTo()).isNull();
                });
        assertThat(versionRepository.findById(tenantId, policyId, draft.id()).orElseThrow())
                .satisfies(version -> {
                    assertThat(version.status()).isEqualTo("DRAFT");
                    assertThat(version.effectiveTo()).isNull();
                });
        assertThat(versionRepository.findById(tenantId, policyId, voided.id()).orElseThrow())
                .satisfies(version -> {
                    assertThat(version.status()).isEqualTo("VOIDED");
                    assertThat(version.effectiveTo()).isEqualTo(EXPIRATION);
                });
    }

    private static PolicyVersion version(UUID tenantId, UUID policyId, int versionNumber,
                                         String status, LocalDate effectiveFrom,
                                         LocalDate effectiveTo) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyVersion(UUID.randomUUID(), tenantId, policyId, versionNumber,
                "POL-2026-0000001-" + String.format("%02d", versionNumber), "ENDORSEMENT",
                status, effectiveFrom, effectiveTo,
                "ISSUED".equals(status) ? now : null, null, null, null, null, null,
                "Seeded version", null, "h".repeat(64), now);
    }

    private static Policy policy(UUID tenantId, UUID policyId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(policyId, tenantId, "POL-2026-0000001", "CGL",
                UUID.randomUUID(), "ISSUED", EFFECTIVE, EXPIRATION,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                4, now, now);
    }
}
