package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndorsementServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private MutationGuard guard;
    @Mock
    private EndorsementRepository endorsementRepository;
    @Mock
    private PolicyVersionService versionService;
    @Mock
    private PolicyVersionRepository versionRepository;
    @Mock
    private VersionIssuer versionIssuer;
    @Mock
    private EndorsementFactory endorsementFactory;
    @Mock
    private OutboxRepository outbox;

    @Test
    void refusesToEndorseAPolicyThatIsNotIssued() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("BOUND"));
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(false);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request(LocalDate.of(2026, 6, 1),
                null, null, null), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("POLICY_NOT_ISSUED");
                });
        verify(endorsementFactory, never()).create(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aBackdatedEndorsementRequiresAReason() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE"));
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request(FROM.minusDays(5),
                null, APPROVER, APPROVAL), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("BACKDATE_REASON_REQUIRED"));
    }

    @Test
    void aBackdatedEndorsementRequiresAnApproval() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE"));
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request(FROM.minusDays(5),
                "Retroactive coverage", null, null), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });
    }

    @Test
    void rejectsAnEndorsementDatedAfterExpiration() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE"));
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request(TO, null, null, null),
                null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("ENDORSEMENT_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void rejectsAPremiumDeltaInAnotherCurrency() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE"));
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);

        CreateEndorsementRequest request = new CreateEndorsementRequest(null, "PREMIUM",
                LocalDate.of(2026, 6, 1), "Premium adjustment", new BigDecimal("1000.00"),
                "EUR", "NONE", null, null, null, null, null, null);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void issuesANewPolicyVersionForAValidEndorsement() {
        Policy policy = policy("IN_FORCE");
        CreateEndorsementRequest request = request(LocalDate.of(2026, 6, 1), null, null, null);
        PolicyVersion version = version();
        PolicyEndorsement endorsement = endorsement();
        when(guard.requirePolicy(TENANT, POLICY_ID, "\"4\"")).thenReturn(policy);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version);
        when(endorsementFactory.create(any(), eq(policy), any(), any(), eq("LIMIT"), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(endorsement);
        when(versionIssuer.issue(eq(policy), any(), eq("ENDORSEMENT"), eq(LocalDate.of(2026, 6, 1)),
                any(), any(), any(), any())).thenReturn(version);
        when(policyRepository.touch(TENANT, POLICY_ID, 4)).thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));

        EndorsementResult result = service().create(TENANT, POLICY_ID, request, "\"4\"", null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.endorsement()).isEqualTo(endorsement);
        assertThat(result.policyVersion()).isEqualTo(4);
        verify(versionIssuer).issue(eq(policy), any(), eq("ENDORSEMENT"),
                eq(LocalDate.of(2026, 6, 1)), any(), any(), any(), any());
    }

    private EndorsementService service() {
        return new EndorsementService(policyRepository, guard, endorsementRepository, versionService,
                versionRepository, versionIssuer, endorsementFactory,
                new CommandIdempotency(policyRepository), outbox);
    }

    private static CreateEndorsementRequest request(LocalDate effectiveDate, String backdateReason,
                                                    UUID approvedBy, UUID approvalRef) {
        return new CreateEndorsementRequest(null, "LIMIT", effectiveDate,
                "Increase the general aggregate", new BigDecimal("5000.00"), "USD", "BROADEN",
                null, backdateReason, approvedBy, approvalRef, null, null);
    }

    private static Policy policy(String status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                4, now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "ENDORSEMENT", "ISSUED", LocalDate.of(2026, 6, 1), null, now, null, null, null,
                new BigDecimal("5000.00"), "USD", "Increase the general aggregate", null,
                "hash", now);
    }

    private static PolicyEndorsement endorsement() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyEndorsement(UUID.randomUUID(), TENANT, POLICY_ID, VERSION_ID,
                "END-0001", 1, null, null, "LIMIT", LocalDate.of(2026, 6, 1),
                "Increase the general aggregate", new BigDecimal("5000.00"), "USD", "BROADEN",
                java.util.List.of(), false, null, null, null, "ISSUED", now, now);
    }
}
