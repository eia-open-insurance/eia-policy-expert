package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
class SubmissionConversionServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID POLICY_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Mock
    private UnderwritingSubmissionRepository submissionRepository;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private PolicyService policyService;

    @Mock
    private CoverageRepository coverageRepository;

    @Mock
    private OutboxRepository outbox;

    private SubmissionConversionService service() {
        return new SubmissionConversionService(submissionRepository, policyRepository,
                policyService, coverageRepository, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void convertsAQuotedSubmissionAndInitializesTheBaseCoverage() {
        UnderwritingSubmission quoted = submission("QUOTED", 2);
        UnderwritingSubmission bound = submission("BOUND", 3);
        Policy draft = policy(0, null);
        Policy linked = policy(1, SUBMISSION_ID);
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(quoted));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());
        when(policyService.create(any(CreatePolicyRequest.class))).thenReturn(draft);
        when(policyRepository.assignSubmission(eq(TENANT), eq(POLICY_ID), eq(SUBMISSION_ID),
                eq(0), any(Instant.class))).thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(linked));
        when(submissionRepository.updateStatus(eq(TENANT), eq(SUBMISSION_ID), eq("BOUND"),
                eq("QUOTED"), eq(2), any(Instant.class))).thenReturn(1);
        when(submissionRepository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(bound));
        when(coverageRepository.nextCoverageSequence(TENANT, POLICY_ID)).thenReturn(1);

        SubmissionConversionResult result = service().convert(TENANT, SUBMISSION_ID,
                new ConvertSubmissionRequest(new BigDecimal("125000"), " usd ", null));

        assertThat(result.replayed()).isFalse();
        assertThat(result.policy().id()).isEqualTo(POLICY_ID);
        assertThat(result.policy().submissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(result.policy().version()).isEqualTo(1);
        assertThat(result.submission().status()).isEqualTo("BOUND");
        assertThat(result.submission().version()).isEqualTo(3);
        assertThat(result.submission().policyId()).isEqualTo(POLICY_ID);

        ArgumentCaptor<CreatePolicyRequest> policyRequest =
                ArgumentCaptor.forClass(CreatePolicyRequest.class);
        verify(policyService).create(policyRequest.capture());
        assertThat(policyRequest.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(policyRequest.getValue().productCode()).isEqualTo("CGL_PRODUCTS");
        assertThat(policyRequest.getValue().insuredPartyId()).isEqualTo(APPLICANT);
        assertThat(policyRequest.getValue().effectiveDate()).isEqualTo(EFFECTIVE);
        assertThat(policyRequest.getValue().expirationDate())
                .isEqualTo(EFFECTIVE.plusYears(1));
        assertThat(policyRequest.getValue().premiumAmount())
                .isEqualByComparingTo("125000.0000");
        assertThat(policyRequest.getValue().premiumCurrency()).isEqualTo("USD");
        assertThat(policyRequest.getValue().limitAmount())
                .isEqualByComparingTo("2500000.0000");
        assertThat(policyRequest.getValue().limitCurrency()).isEqualTo("USD");
        assertThat(policyRequest.getValue().status()).isEqualTo("DRAFT");

        ArgumentCaptor<PolicyCoverage> coverage = ArgumentCaptor.forClass(PolicyCoverage.class);
        ArgumentCaptor<PolicyLimit> limit = ArgumentCaptor.forClass(PolicyLimit.class);
        ArgumentCaptor<PolicyLimitState> state = ArgumentCaptor.forClass(PolicyLimitState.class);
        verify(coverageRepository).insertCoverage(coverage.capture());
        verify(coverageRepository).insertLimit(limit.capture());
        verify(coverageRepository).insertState(state.capture());
        assertThat(coverage.getValue().coverageCode()).isEqualTo("CGL_PRODUCTS");
        assertThat(coverage.getValue().effectiveTo()).isEqualTo(EFFECTIVE.plusYears(1));
        assertThat(limit.getValue().perOccurrenceAmount())
                .isEqualByComparingTo("2500000.0000");
        assertThat(limit.getValue().aggregateAmount())
                .isEqualByComparingTo("2500000.0000");
        assertThat(limit.getValue().currency()).isEqualTo("USD");
        assertThat(state.getValue().remainingAmount()).isEqualByComparingTo("2500000.0000");

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.submission.converted");
        assertThat(event.aggregateType()).isEqualTo("UnderwritingSubmission");
        assertThat(event.aggregateId()).isEqualTo(SUBMISSION_ID);
        assertThat(event.aggregateVersion()).isEqualTo(4L);
        assertThat(event.payload())
                .contains("\"submissionId\":\"" + SUBMISSION_ID + "\"")
                .contains("\"submissionNumber\":\"SUB-2026-0000001\"")
                .contains("\"policyId\":\"" + POLICY_ID + "\"")
                .contains("\"policyNumber\":\"POL-2026-0000001\"");
    }

    @Test
    void replayingABoundSubmissionReturnsTheExistingPolicyWithoutWriting() {
        UnderwritingSubmission bound = submission("BOUND", 3);
        Policy existing = policy(1, SUBMISSION_ID);
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(bound));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(existing));

        SubmissionConversionResult result = service().convert(TENANT, SUBMISSION_ID,
                new ConvertSubmissionRequest(new BigDecimal("1"), "USD", null));

        assertThat(result.replayed()).isTrue();
        assertThat(result.policy()).isEqualTo(existing);
        assertThat(result.submission().policyId()).isEqualTo(POLICY_ID);
        verifyNoInteractions(policyService, coverageRepository, outbox);
        verify(submissionRepository, never()).updateStatus(any(), any(), any(), any(),
                anyInt(), any());
        verify(policyRepository, never()).assignSubmission(any(), any(), any(), anyInt(), any());
    }

    @Test
    void aReceivedSubmissionIsNotConvertible() {
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().convert(TENANT, SUBMISSION_ID, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("SUBMISSION_NOT_CONVERTIBLE");
                    assertThat(api.meta())
                            .containsEntry("currentStatus", "RECEIVED")
                            .containsEntry("submissionId", SUBMISSION_ID.toString());
                });

        verifyNoInteractions(policyService, coverageRepository, outbox);
    }

    @Test
    void anInvalidExpirationIsRejectedBeforeThePolicyIsCreated() {
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("QUOTED", 2)));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().convert(TENANT, SUBMISSION_ID,
                new ConvertSubmissionRequest(new BigDecimal("100"), "USD", EFFECTIVE)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_CONVERSION_EXPIRATION");
                });

        verifyNoInteractions(policyService, coverageRepository, outbox);
    }

    @Test
    void aBoundSubmissionWithoutAPolicyIsLinkedAndVersionBumped() {
        UnderwritingSubmission bound = submission("BOUND", 3);
        UnderwritingSubmission linkedSubmission = submission("BOUND", 4);
        Policy draft = policy(0, null);
        Policy linked = policy(1, SUBMISSION_ID);
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(bound));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());
        when(policyService.create(any(CreatePolicyRequest.class))).thenReturn(draft);
        when(policyRepository.assignSubmission(eq(TENANT), eq(POLICY_ID), eq(SUBMISSION_ID),
                eq(0), any(Instant.class))).thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(linked));
        when(submissionRepository.touch(eq(TENANT), eq(SUBMISSION_ID), eq(3),
                any(Instant.class))).thenReturn(1);
        when(submissionRepository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(linkedSubmission));
        when(coverageRepository.nextCoverageSequence(TENANT, POLICY_ID)).thenReturn(1);

        SubmissionConversionResult result = service().convert(TENANT, SUBMISSION_ID,
                new ConvertSubmissionRequest(new BigDecimal("100"), "USD", null));

        assertThat(result.replayed()).isFalse();
        assertThat(result.submission().status()).isEqualTo("BOUND");
        assertThat(result.submission().version()).isEqualTo(4);
        verify(submissionRepository, never()).updateStatus(any(), any(), any(), any(),
                anyInt(), any());
        verify(submissionRepository).touch(eq(TENANT), eq(SUBMISSION_ID), eq(3),
                any(Instant.class));
        assertThat(onlyEvent().aggregateVersion()).isEqualTo(5L);
    }

    @Test
    void aZeroLimitSkipsTheCoverageInitialization() {
        UnderwritingSubmission quoted = submission("QUOTED", 2, BigDecimal.ZERO);
        Policy draft = policy(0, null);
        Policy linked = policy(1, SUBMISSION_ID);
        when(submissionRepository.findByIdForUpdate(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(quoted));
        when(policyRepository.findBySubmissionId(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());
        when(policyService.create(any(CreatePolicyRequest.class))).thenReturn(draft);
        when(policyRepository.assignSubmission(eq(TENANT), eq(POLICY_ID), eq(SUBMISSION_ID),
                eq(0), any(Instant.class))).thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(linked));
        when(submissionRepository.updateStatus(eq(TENANT), eq(SUBMISSION_ID), eq("BOUND"),
                eq("QUOTED"), eq(2), any(Instant.class))).thenReturn(1);
        when(submissionRepository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("BOUND", 3)));

        service().convert(TENANT, SUBMISSION_ID, request());

        verifyNoInteractions(coverageRepository);
        verify(outbox).insert(any(OutboxEvent.class));
    }

    @Test
    void aMissingSubmissionIsNotFoundEvenForAnotherTenant() {
        when(submissionRepository.findByIdForUpdate(OTHER_TENANT, SUBMISSION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().convert(OTHER_TENANT, SUBMISSION_ID, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("SUBMISSION_NOT_FOUND");
                });

        verifyNoInteractions(policyService, coverageRepository, outbox);
    }

    @Test
    void normalizesTheCoverageCodeToTheCoverageAlphabet() {
        assertThat(SubmissionConversionService.defaultCoverageCode("cgl-products"))
                .isEqualTo("CGL_PRODUCTS");
        assertThat(SubmissionConversionService.defaultCoverageCode("9 / 11"))
                .isEqualTo("C_9___11");
        assertThat(SubmissionConversionService.defaultCoverageCode("P".repeat(40)))
                .hasSize(32);
    }

    private OutboxEvent onlyEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static ConvertSubmissionRequest request() {
        return new ConvertSubmissionRequest(new BigDecimal("125000"), "USD", null);
    }

    private static UnderwritingSubmission submission(String status, int version) {
        return submission(status, version, new BigDecimal("2500000.0000"));
    }

    private static UnderwritingSubmission submission(String status, int version,
                                                     BigDecimal requestedLimit) {
        return new UnderwritingSubmission(SUBMISSION_ID, TENANT, "SUB-2026-0000001",
                APPLICANT, "CGL_PRODUCTS", EFFECTIVE, requestedLimit, "USD",
                List.of("ASBESTOS"), status, null, version, NOW, NOW);
    }

    private static Policy policy(int version, UUID submissionId) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL_PRODUCTS", APPLICANT,
                "DRAFT", EFFECTIVE, EFFECTIVE.plusYears(1), new BigDecimal("125000.0000"),
                "USD", new BigDecimal("2500000.0000"), "USD", version, NOW, NOW, null, null,
                null, submissionId);
    }
}
