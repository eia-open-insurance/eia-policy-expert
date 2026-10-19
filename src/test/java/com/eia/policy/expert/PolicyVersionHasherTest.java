package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyVersionHasherTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID COVERAGE_ID = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final UUID LIMIT_ID = UUID.fromString("88888888-8888-7888-8888-888888888888");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Test
    void hashIsDeterministicForTheSameSnapshot() {
        String first = PolicyVersionHasher.hash(policy(), version(), snapshot("2000000"));
        String second = PolicyVersionHasher.hash(policy(), version(), snapshot("2000000"));

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void hashIsIndependentOfChildRowOrder() {
        PolicySnapshot forward = snapshot("2000000");
        PolicySnapshot reversed = new PolicySnapshot(
                forward.coverages().reversed(),
                forward.limits().reversed(),
                forward.insureds().reversed(),
                forward.locations().reversed(),
                forward.endorsements().reversed());

        assertThat(PolicyVersionHasher.hash(policy(), version(), forward))
                .isEqualTo(PolicyVersionHasher.hash(policy(), version(), reversed));
    }

    @Test
    void hashChangesWhenACoverageAmountChanges() {
        assertThat(PolicyVersionHasher.hash(policy(), version(), snapshot("2000000")))
                .isNotEqualTo(PolicyVersionHasher.hash(policy(), version(), snapshot("2500000")));
    }

    @Test
    void hashChangesWhenTheVersionMetadataChanges() {
        PolicyVersion other = new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 2,
                "POL-2026-0000001-02", "ENDORSEMENT", "ISSUED", FROM, null, Instant.now(),
                null, null, null, new BigDecimal("500.0000"), "USD", "Premium endorsement",
                null, "hash", Instant.now());

        assertThat(PolicyVersionHasher.hash(policy(), version(), snapshot("2000000")))
                .isNotEqualTo(PolicyVersionHasher.hash(policy(), other, snapshot("2000000")));
    }

    private static Policy policy() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "IN_FORCE", FROM, TO,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                5, now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 1, "POL-2026-0000001-01",
                "ISSUE", "ISSUED", FROM, null, now, null, null, null, null, null,
                "Policy issued", null, "hash", now);
    }

    private static PolicySnapshot snapshot(String perOccurrence) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PolicyCoverage coverage = new PolicyCoverage(COVERAGE_ID, TENANT, POLICY_ID,
                "GL_PRODUCTS", "Products liability", "A", "OCCURRENCE", new BigDecimal("0.10"),
                FROM, TO, "ACTIVE", 1, now);
        PolicyLimit limit = new PolicyLimit(LIMIT_ID, TENANT, POLICY_ID, COVERAGE_ID,
                new BigDecimal(perOccurrence), new BigDecimal("5000000"), "PER_COVERAGE",
                new BigDecimal("1000000"), new BigDecimal("5000"), new BigDecimal("0.10"),
                "USD", "PAID_PLUS_RESERVED", FROM, TO, 1, now);
        PolicyInsured insured = new PolicyInsured(UUID.randomUUID(), TENANT, POLICY_ID,
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "NAMED_INSURED",
                "Acme Chemical Corporation", null, null, FROM, TO, "ACTIVE", now);
        PolicyLocation location = new PolicyLocation(UUID.randomUUID(), TENANT, POLICY_ID,
                "LOC-001", "1200 Industrial Way", null, "Akron", "US-OH", "44311", "USA",
                FROM, TO, "ACTIVE", now);
        PolicyEndorsement endorsement = new PolicyEndorsement(UUID.randomUUID(), TENANT,
                POLICY_ID, VERSION_ID, "END-0001", 1, null, null, "COVERAGE", FROM,
                "Coverage added", BigDecimal.ZERO.setScale(4), "USD", "BROADEN",
                List.of(new EndorsementCoverageChange("GL_PRODUCTS", "ADD", "Products")),
                false, null, null, null, "ISSUED", now, now);
        return new PolicySnapshot(List.of(coverage), List.of(limit), List.of(insured),
                List.of(location), List.of(endorsement));
    }
}
