package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private MutationGuard guard;
    @Mock
    private CancellationRepository cancellationRepository;
    @Mock
    private CreditNoteRepository creditNoteRepository;
    @Mock
    private PolicyVersionService versionService;
    @Mock
    private VersionIssuer versionIssuer;
    @Mock
    private EndorsementFactory endorsementFactory;
    @Mock
    private OutboxRepository outbox;

    @Test
    void cancelsAProRataPolicyAndRecordsTheReturnPremium() {
        Policy policy = policy("IN_FORCE", 4);
        Policy cancelled = policy("CANCELLED", 5);
        when(guard.requirePolicy(TENANT, POLICY_ID, "\"4\"")).thenReturn(policy);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version());
        when(cancellationRepository.nextCancellationNumber()).thenReturn("CX-0000001");
        when(policyRepository.updateStatus(TENANT, POLICY_ID, "CANCELLED", "Insured sold the business",
                4)).thenReturn(cancelled);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(cancelled));

        CancellationResult result = service().cancel(TENANT, POLICY_ID,
                new CancelPolicyRequest("PRO_RATA", "Insured sold the business",
                        LocalDate.of(2026, 7, 2), 0, null, null), "\"4\"", null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.cancellation().cancellationType()).isEqualTo("PRO_RATA");
        assertThat(result.cancellation().cancellationNumber()).isEqualTo("CX-0000001");
        assertThat(result.cancellation().termDays()).isEqualTo(365);
        assertThat(result.cancellation().remainingDays()).isEqualTo(183);
        assertThat(result.cancellation().returnPremiumAmount()).isEqualByComparingTo("60164.4000");
        assertThat(result.cancellation().earnedPremiumAmount()).isEqualByComparingTo("59835.6000");
        assertThat(result.policy().status()).isEqualTo("CANCELLED");
        verify(cancellationRepository).insertCancellation(any());
        verify(versionIssuer).issue(eq(policy), any(), eq("CANCELLATION"), any(), any(), any(),
                any(), any());
    }

    @Test
    void refusesToCancelADraftPolicy() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("DRAFT", 0));

        assertThatThrownBy(() -> service().cancel(TENANT, POLICY_ID,
                new CancelPolicyRequest("FLAT", "Withdrawn", LocalDate.of(2026, 3, 1), 0, null, null),
                null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CANCELLATION_NOT_ALLOWED");
                });
        verify(cancellationRepository, never()).insertCancellation(any());
    }

    @Test
    void rejectsACancellationDateOutsideTheTerm() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE", 4));

        assertThatThrownBy(() -> service().cancel(TENANT, POLICY_ID,
                new CancelPolicyRequest("PRO_RATA", "Insured request", TO, 0, null, null),
                null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("CANCELLATION_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void rejectsNonPaymentCancellationWithoutStatutoryNotice() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE", 4));

        assertThatThrownBy(() -> service().cancel(TENANT, POLICY_ID,
                new CancelPolicyRequest("NON_PAYMENT", "Non-payment", LocalDate.of(2026, 7, 2), 3,
                        null, null), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INSUFFICIENT_CANCELLATION_NOTICE"));
    }

    @Test
    void reinstatementRequiresApproval() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("CANCELLED", 5));
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation()));

        assertThatThrownBy(() -> service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", null, null,
                        "RCPT-1", new BigDecimal("1000.00"), "USD"), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });
    }

    @Test
    void reinstatementRequiresPremiumEvidence() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("CANCELLED", 5));
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation()));

        assertThatThrownBy(() -> service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", APPROVER,
                        APPROVAL, " ", new BigDecimal("1000.00"), "USD"), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("PREMIUM_EVIDENCE_REQUIRED"));
    }

    @Test
    void reinstatementCannotPrecedeTheCancellation() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("CANCELLED", 5));
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation()));

        assertThatThrownBy(() -> service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 5, 1), "Premium received", APPROVER,
                        APPROVAL, "RCPT-1", new BigDecimal("1000.00"), "USD"), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("REINSTATEMENT_BEFORE_CANCELLATION"));
    }

    @Test
    void reinstatesACancelledPolicyAfterApprovalAndPremiumEvidence() {
        Policy policy = policy("CANCELLED", 5);
        Policy reinstated = policy("REINSTATED", 6);
        PolicyCancellation cancellation = cancellation();
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation));
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version());
        when(cancellationRepository.markReinstated(TENANT, POLICY_ID, cancellation.id()))
                .thenReturn(1);
        when(policyRepository.updateStatus(TENANT, POLICY_ID, "REINSTATED", "Premium received", 5))
                .thenReturn(reinstated);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(reinstated));

        ReinstatementResult result = service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", APPROVER,
                        APPROVAL, "RCPT-1", new BigDecimal("1000.00"), "USD"), null, null);

        assertThat(result.reinstatement().status()).isEqualTo("APPROVED");
        assertThat(result.reinstatement().premiumEvidenceRef()).isEqualTo("RCPT-1");
        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        verify(cancellationRepository).insertReinstatement(any());
        verify(versionIssuer).issue(eq(policy), any(), eq("REINSTATEMENT"), any(), any(), any(),
                any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ISSUED", "APPLIED"})
    void reinstatementIsBlockedWhileTheLinkedCreditNoteIsOpen(String status) {
        PolicyCancellation cancellation = cancellation();
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("CANCELLED", 5));
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation));
        when(creditNoteRepository.findAllByCancellation(TENANT, POLICY_ID, cancellation.id()))
                .thenReturn(List.of(creditNote("CN-0000001", status)));

        assertThatThrownBy(() -> service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", APPROVER,
                        APPROVAL, "RCPT-1", new BigDecimal("1000.00"), "USD"),
                null, "guard-" + status))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CANCELLATION_CREDIT_NOTE_OPEN");
                    assertThat(api.getMessage())
                            .contains("CN-0000001")
                            .contains("Cancel or reverse");
                    assertThat(api.meta()).containsEntry("noteNumbers", List.of("CN-0000001"));
                });

        verify(cancellationRepository, never()).insertReinstatement(any());
        verify(cancellationRepository, never()).markReinstated(any(), any(), any());
        verify(policyRepository, never()).updateStatus(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(versionIssuer, endorsementFactory, outbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "REVERSED"})
    void reinstatementProceedsAfterTheLinkedCreditNoteIsClosed(String status) {
        PolicyCancellation cancellation = cancellation();
        Policy policy = policy("CANCELLED", 5);
        Policy reinstated = policy("REINSTATED", 6);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(cancellationRepository.findActiveCancellation(TENANT, POLICY_ID))
                .thenReturn(Optional.of(cancellation));
        when(creditNoteRepository.findAllByCancellation(TENANT, POLICY_ID, cancellation.id()))
                .thenReturn(List.of(creditNote("CN-0000001", status)));
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version());
        when(versionIssuer.issue(eq(policy), any(), eq("REINSTATEMENT"), any(), any(), any(),
                any(), any())).thenReturn(version());
        when(cancellationRepository.markReinstated(TENANT, POLICY_ID, cancellation.id()))
                .thenReturn(1);
        when(policyRepository.updateStatus(TENANT, POLICY_ID, "REINSTATED", "Premium received", 5))
                .thenReturn(reinstated);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(reinstated));

        ReinstatementResult result = service().reinstate(TENANT, POLICY_ID,
                new ReinstatePolicyRequest(LocalDate.of(2026, 8, 1), "Premium received", APPROVER,
                        APPROVAL, "RCPT-1", new BigDecimal("1000.00"), "USD"), null, null);

        assertThat(result.reinstatement().cancellationId()).isEqualTo(cancellation.id());
        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        verify(cancellationRepository).insertReinstatement(any());
        verify(outbox, org.mockito.Mockito.times(2)).insert(any());
    }

    private CancellationService service() {
        return new CancellationService(policyRepository, guard, cancellationRepository,
                creditNoteRepository, versionService, versionIssuer, endorsementFactory,
                new CommandIdempotency(policyRepository), outbox);
    }

    private static PolicyCreditNote creditNote(String noteNumber, String status) {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        return new PolicyCreditNote(UUID.randomUUID(), TENANT, POLICY_ID, noteNumber,
                new BigDecimal("60164.4000"), "USD", "cancellation return premium", status,
                null, null, now);
    }

    private static PolicyCancellation cancellation() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new PolicyCancellation(UUID.randomUUID(), TENANT, POLICY_ID,
                UUID.randomUUID(), "PRO_RATA", "Insured sold the business",
                LocalDate.of(2026, 7, 2), 0, now, 365, 183, new BigDecimal("0.501370"),
                new BigDecimal("59835.6000"), new BigDecimal("60164.4000"), "USD", APPROVAL,
                APPROVER, "ACTIVE", now);
    }

    private static Policy policy(String status, int version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("120000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                version, now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(UUID.randomUUID(), TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "ENDORSEMENT", "ISSUED", LocalDate.of(2026, 6, 1), null, now, null, null, null,
                null, null, "Endorsement", null, "hash", now);
    }
}
