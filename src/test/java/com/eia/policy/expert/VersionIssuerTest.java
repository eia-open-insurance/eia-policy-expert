package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VersionIssuerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate CUTOVER = LocalDate.of(2026, 6, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyVersionRepository versionRepository;

    @Mock
    private PolicySnapshotLoader snapshotLoader;

    @Test
    void determinesThePredecessorBeforeClosingAllOpenVersionsThenValidatesAndInserts() {
        Policy policy = policy();
        PolicyVersion current = currentVersion();
        when(versionRepository.nextVersionNumber(TENANT, POLICY_ID)).thenReturn(4);
        when(versionRepository.findLatest(TENANT, POLICY_ID)).thenReturn(Optional.of(current));
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());
        when(versionRepository.findWindows(TENANT, POLICY_ID)).thenReturn(List.of(
                new VersionTimeline.Window(current.id(), EFFECTIVE, CUTOVER, "SUPERSEDED")));

        PolicyVersion issued = new VersionIssuer(versionRepository, snapshotLoader)
                .issue(policy, VERSION_ID, "ENDORSEMENT", CUTOVER,
                        "Increase the general aggregate", new BigDecimal("5000.00"), "USD", null);

        assertThat(issued.supersedesVersionId()).isEqualTo(current.id());
        assertThat(issued.versionNumber()).isEqualTo(4);
        InOrder order = inOrder(versionRepository);
        order.verify(versionRepository).findLatest(TENANT, POLICY_ID);
        order.verify(versionRepository).supersedeAllOpen(TENANT, POLICY_ID, CUTOVER);
        order.verify(versionRepository).findWindows(TENANT, POLICY_ID);
        order.verify(versionRepository).insert(issued);
    }

    private static Policy policy() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "ISSUED",
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", 4, now, now);
    }

    private static PolicyVersion currentVersion() {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 3,
                "POL-2026-0000001-03", "ENDORSEMENT", "ISSUED", EFFECTIVE, null,
                now, null, null, null, new BigDecimal("1000.00"), "USD", "Prior endorsement",
                null, "hash", now);
    }
}
