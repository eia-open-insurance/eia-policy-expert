package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnderwritingSubmissionServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Mock
    private UnderwritingSubmissionRepository repository;

    @Mock
    private UnderwritingSubmissionNumberAllocator numberAllocator;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private ProductCatalogGuard productCatalog;

    @Mock
    private OutboxRepository outbox;

    private UnderwritingSubmissionService service() {
        return new UnderwritingSubmissionService(repository, numberAllocator, policyRepository,
                productCatalog, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAReceivedSubmissionWithAPerTenantNumberAndCreatedEvent() {
        when(numberAllocator.next(TENANT)).thenReturn(1L);

        UnderwritingSubmissionView view = service().create(TENANT, request());

        assertThat(view.submissionId()).isNotNull();
        assertThat(view.submissionNumber()).isEqualTo("SUB-2026-0000001");
        assertThat(view.applicantPartyId()).isEqualTo(APPLICANT);
        assertThat(view.productCode()).isEqualTo("CGL_PRODUCTS");
        assertThat(view.requestedEffectiveDate()).isEqualTo(EFFECTIVE);
        assertThat(view.requestedLimitAmount()).isEqualByComparingTo("2500000.0000");
        assertThat(view.requestedLimitCurrency()).isEqualTo("USD");
        assertThat(view.tortCodes()).containsExactly("ASBESTOS", "PFAS");
        assertThat(view.status()).isEqualTo("RECEIVED");
        assertThat(view.notes()).isEqualTo("Large manufacturing risk");
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW);

        ArgumentCaptor<UnderwritingSubmission> inserted =
                ArgumentCaptor.forClass(UnderwritingSubmission.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(inserted.getValue().status()).isEqualTo("RECEIVED");
        assertThat(inserted.getValue().version()).isZero();

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.submission.created");
        assertThat(event.aggregateType()).isEqualTo("UnderwritingSubmission");
        assertThat(event.aggregateId()).isEqualTo(view.submissionId());
        assertThat(event.aggregateVersion()).isEqualTo(1L);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.partitionKey()).isEqualTo(view.submissionId().toString());
        assertThat(event.payload())
                .contains("\"submissionId\":\"" + view.submissionId() + "\"")
                .contains("\"submissionNumber\":\"SUB-2026-0000001\"")
                .contains("\"applicantPartyId\":\"" + APPLICANT + "\"")
                .contains("\"productCode\":\"CGL_PRODUCTS\"")
                .contains("\"requestedEffectiveDate\":\"2026-11-01\"")
                .contains("\"requestedLimitAmount\":\"2500000.0000\"")
                .contains("\"requestedLimitCurrency\":\"USD\"")
                .contains("\"tortCodes\":[\"ASBESTOS\",\"PFAS\"]")
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"notes\":\"Large manufacturing risk\"")
                .contains("\"version\":0")
                .contains("\"createdAt\":\"2026-10-22T12:00:00Z\"");
    }

    @Test
    void normalizesInputsAndEmitsNullNotes() {
        when(numberAllocator.next(TENANT)).thenReturn(7L);

        UnderwritingSubmissionView view = service().create(TENANT,
                new CreateUnderwritingSubmissionRequest(APPLICANT, "  cgl  ", EFFECTIVE,
                        new BigDecimal("0"), " usd ", null, "   "));

        assertThat(view.submissionNumber()).isEqualTo("SUB-2026-0000007");
        assertThat(view.productCode()).isEqualTo("cgl");
        assertThat(view.requestedLimitCurrency()).isEqualTo("USD");
        assertThat(view.tortCodes()).isEmpty();
        assertThat(view.notes()).isNull();
        assertThat(onlyEvent().payload()).contains("\"notes\":null")
                .contains("\"tortCodes\":[]");
    }

    @Test
    void retriesTheFirstUseNumberRace() {
        when(numberAllocator.next(TENANT))
                .thenThrow(new DuplicateKeyException("counter race"))
                .thenReturn(1L);

        UnderwritingSubmissionView view = service().create(TENANT, request());

        assertThat(view.submissionNumber()).isEqualTo("SUB-2026-0000001");
        verify(numberAllocator, org.mockito.Mockito.times(2)).next(TENANT);
    }

    @Test
    void aDuplicateSubmissionNumberIsAConflictWithoutAnEvent() {
        when(numberAllocator.next(TENANT)).thenReturn(1L);
        doThrow(new DuplicateKeyException("duplicate")).when(repository)
                .insert(any(UnderwritingSubmission.class));

        assertThatThrownBy(() -> service().create(TENANT, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_SUBMISSION_NUMBER");
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void checksTheActiveProductBeforeAllocatingTheNumber() {
        when(numberAllocator.next(TENANT)).thenReturn(1L);

        service().create(TENANT, request());

        verify(productCatalog).requireActive(TENANT, "CGL_PRODUCTS");
    }

    @Test
    void anUnknownProductIsRejectedBeforeTheNumberIsAllocated() {
        ApiException rejected = PolicyErrors.businessRule("UNKNOWN_PRODUCT", "productCode",
                "Product code CGL_PRODUCTS does not exist in this tenant's product catalog.");
        doThrow(rejected).when(productCatalog).requireActive(TENANT, "CGL_PRODUCTS");

        assertThatThrownBy(() -> service().create(TENANT, request())).isSameAs(rejected);

        verifyNoInteractions(repository, numberAllocator, outbox);
    }

    @Test
    void invalidCreateDoesNotAllocateAValidateOrWriteAnEvent() {
        assertThatThrownBy(() -> service().create(TENANT,
                new CreateUnderwritingSubmissionRequest(APPLICANT, "P".repeat(65), EFFECTIVE,
                        BigDecimal.ONE, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_PRODUCT_CODE"));

        verifyNoInteractions(repository, numberAllocator, productCatalog, outbox);
    }

    @Test
    void listsWithNormalizedFiltersAndTenantScope() {
        when(repository.findAll(TENANT, "sub", "RECEIVED"))
                .thenReturn(List.of(submission("RECEIVED", 0)));

        List<UnderwritingSubmissionView> result = service().list(TENANT, "  sub  ", "received");

        assertThat(result).singleElement()
                .satisfies(view -> assertThat(view.submissionNumber())
                        .isEqualTo("SUB-2026-0000001"));
        verify(repository).findAll(TENANT, "sub", "RECEIVED");
    }

    @Test
    void blankListFiltersAreOmitted() {
        when(repository.findAll(TENANT, null, null)).thenReturn(List.of());

        assertThat(service().list(TENANT, "   ", "  ")).isEmpty();

        verify(repository).findAll(TENANT, null, null);
    }

    @Test
    void anUnknownStatusFilterIsARequestError() {
        assertThatThrownBy(() -> service().list(TENANT, null, "APPROVED"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_STATUS");
                });

        verifyNoInteractions(repository);
    }

    @Test
    void getsOneSubmissionOnTheRequestedTenant() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));

        assertThat(service().get(TENANT, SUBMISSION_ID).submissionId())
                .isEqualTo(SUBMISSION_ID);
        verify(repository).findById(TENANT, SUBMISSION_ID);
    }

    @Test
    void aSubmissionFromAnotherTenantIsNotFound() {
        when(repository.findById(OTHER_TENANT, SUBMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(OTHER_TENANT, SUBMISSION_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("SUBMISSION_NOT_FOUND");
                });
    }

    @Test
    void transitionsReceivedToReferredAndEmitsTheStatusChangedEvent() {
        UnderwritingSubmission before = submission("RECEIVED", 0);
        UnderwritingSubmission after = submission("REFERRED", 1);
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(repository.updateStatus(eq(TENANT), eq(SUBMISSION_ID), eq("REFERRED"),
                eq("RECEIVED"), eq(0), any(Instant.class))).thenReturn(1);

        UnderwritingSubmissionView view = service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("REFERRED",
                        "  Large limit needs review  "));

        assertThat(view.status()).isEqualTo("REFERRED");
        assertThat(view.version()).isEqualTo(1);

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.submission.status-changed");
        assertThat(event.aggregateType()).isEqualTo("UnderwritingSubmission");
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.payload())
                .contains("\"submissionId\":\"" + SUBMISSION_ID + "\"")
                .contains("\"submissionNumber\":\"SUB-2026-0000001\"")
                .contains("\"previousStatus\":\"RECEIVED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"version\":1")
                .contains("\"reason\":\"Large limit needs review\"");
    }

    @Test
    void anOmittedReasonIsEmittedAsJsonNull() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)))
                .thenReturn(Optional.of(submission("QUOTED", 1)));
        when(repository.updateStatus(eq(TENANT), eq(SUBMISSION_ID), eq("QUOTED"),
                eq("RECEIVED"), eq(0), any(Instant.class))).thenReturn(1);

        service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("QUOTED", "   "));

        assertThat(onlyEvent().payload()).contains("\"reason\":null");
    }

    @Test
    void anOutOfOrderTransitionIsAConflictWithoutAWriteOrEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("BOUND", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_TRANSITION");
                    assertThat(api.meta())
                            .containsEntry("currentStatus", "RECEIVED")
                            .containsEntry("targetStatus", "BOUND");
                });

        verify(repository, never()).updateStatus(any(), any(), anyString(), anyString(),
                anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aTerminalSubmissionCannotTransitionAgain() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("BOUND", 3)));

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("WITHDRAWN", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_TRANSITION"));

        verifyNoInteractions(outbox);
    }

    @Test
    void anUnknownTargetStatusIsARequestError() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("APPROVED", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_STATUS");
                });
    }

    @Test
    void anOverlongReasonIsARequestErrorBeforeTheWrite() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("REFERRED", "R".repeat(501))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_REASON"));

        verify(repository, never()).updateStatus(any(), any(), anyString(), anyString(),
                anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentStatusChangeIsAConflictWithoutAnEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)))
                .thenReturn(Optional.of(submission("QUOTED", 1)));
        when(repository.updateStatus(eq(TENANT), eq(SUBMISSION_ID), eq("REFERRED"),
                eq("RECEIVED"), eq(0), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("REFERRED", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta())
                            .containsEntry("currentVersion", 1)
                            .containsEntry("expectedVersion", 0);
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void updatingAMissingSubmissionIsNotFound() {
        when(repository.findById(TENANT, SUBMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateStatus(TENANT, SUBMISSION_ID,
                new UpdateUnderwritingSubmissionStatusRequest("REFERRED", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("SUBMISSION_NOT_FOUND"));

        verifyNoInteractions(outbox);
    }

    @Test
    void opensAReferralFromReceivedAndEmitsTheReferredEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 1)));
        when(repository.updateReferral(eq(TENANT), eq(SUBMISSION_ID), eq("RECEIVED"), eq(0),
                any(UnderwritingSubmissionRules.ReferralSpec.class), any(Instant.class)))
                .thenReturn(1);

        UnderwritingSubmissionView view = service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("  Aggregate limit above authority  ",
                        "Chief Underwriting Officer", "Senior UW - Casualty",
                        "  Needs reinsurance sign-off  "));

        assertThat(view.status()).isEqualTo("REFERRED");
        assertThat(view.referralReason()).isEqualTo("Aggregate limit above authority");
        assertThat(view.referralAuthority()).isEqualTo("Chief Underwriting Officer");
        assertThat(view.referredTo()).isEqualTo("Senior UW - Casualty");
        assertThat(view.referralNotes()).isEqualTo("Needs reinsurance sign-off");
        assertThat(view.referralDecision()).isEqualTo("PENDING");
        assertThat(view.referralDecidedBy()).isNull();
        assertThat(view.referralDecidedAt()).isNull();
        assertThat(view.version()).isEqualTo(1);

        ArgumentCaptor<UnderwritingSubmissionRules.ReferralSpec> spec =
                ArgumentCaptor.forClass(UnderwritingSubmissionRules.ReferralSpec.class);
        verify(repository).updateReferral(eq(TENANT), eq(SUBMISSION_ID), eq("RECEIVED"),
                eq(0), spec.capture(), any(Instant.class));
        assertThat(spec.getValue().reason()).isEqualTo("Aggregate limit above authority");
        assertThat(spec.getValue().notes()).isEqualTo("Needs reinsurance sign-off");

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.submission.referred");
        assertThat(event.aggregateType()).isEqualTo("UnderwritingSubmission");
        assertThat(event.aggregateId()).isEqualTo(SUBMISSION_ID);
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.partitionKey()).isEqualTo(SUBMISSION_ID.toString());
        assertThat(event.payload())
                .contains("\"submissionId\":\"" + SUBMISSION_ID + "\"")
                .contains("\"submissionNumber\":\"SUB-2026-0000001\"")
                .contains("\"previousStatus\":\"RECEIVED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"referralReason\":\"Aggregate limit above authority\"")
                .contains("\"referralAuthority\":\"Chief Underwriting Officer\"")
                .contains("\"referredTo\":\"Senior UW - Casualty\"")
                .contains("\"referralNotes\":\"Needs reinsurance sign-off\"")
                .contains("\"referralDecision\":\"PENDING\"")
                .contains("\"version\":1");
    }

    @Test
    void reRefersAnAlreadyReferredSubmissionAndResetsThePendingDecision() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "APPROVED",
                        "Chief Underwriter", NOW, 2)))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 3)));
        when(repository.updateReferral(eq(TENANT), eq(SUBMISSION_ID), eq("REFERRED"), eq(2),
                any(UnderwritingSubmissionRules.ReferralSpec.class), any(Instant.class)))
                .thenReturn(1);

        UnderwritingSubmissionView view = service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("New evidence", "Authority", "Desk", null));

        assertThat(view.status()).isEqualTo("REFERRED");
        assertThat(view.referralDecision()).isEqualTo("PENDING");
        assertThat(view.version()).isEqualTo(3);
        assertThat(onlyEvent().payload())
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"referralDecision\":\"PENDING\"")
                .contains("\"version\":3");
    }

    @Test
    void aSubmissionPastReferralCannotBeReferred() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("QUOTED", 2)));

        assertThatThrownBy(() -> service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("Reason", "Authority", "Assignee", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("SUBMISSION_NOT_REFERRABLE");
                    assertThat(api.meta())
                            .containsEntry("currentStatus", "QUOTED");
                });

        verify(repository, never()).updateReferral(any(), any(), anyString(), anyInt(),
                any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void anInvalidReferralBodyIsRejectedBeforeTheWrite() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)));

        assertThatThrownBy(() -> service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("R".repeat(65), "Authority", "Assignee", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_REFERRAL_REASON"));

        verify(repository, never()).updateReferral(any(), any(), anyString(), anyInt(),
                any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentReferralIsAConflictWithoutAnEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("RECEIVED", 0)))
                .thenReturn(Optional.of(submission("REFERRED", 1)));
        when(repository.updateReferral(eq(TENANT), eq(SUBMISSION_ID), eq("RECEIVED"), eq(0),
                any(UnderwritingSubmissionRules.ReferralSpec.class), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("Reason", "Authority", "Assignee", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta())
                            .containsEntry("currentVersion", 1)
                            .containsEntry("expectedVersion", 0);
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void approvingAPendingReferralKeepsTheSubmissionReferred() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 1)))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "APPROVED",
                        "Chief Underwriter", NOW, "Approved within authority", 2)));
        when(repository.decideReferral(eq(TENANT), eq(SUBMISSION_ID), eq("REFERRED"),
                eq("APPROVED"), eq("Chief Underwriter"), eq("Approved within authority"),
                eq("REFERRED"), eq(1), any(Instant.class))).thenReturn(1);

        UnderwritingSubmissionView view = service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest(" approved ", "  Chief Underwriter  ",
                        "Approved within authority"));

        assertThat(view.status()).isEqualTo("REFERRED");
        assertThat(view.referralDecision()).isEqualTo("APPROVED");
        assertThat(view.referralDecidedBy()).isEqualTo("Chief Underwriter");
        assertThat(view.referralDecidedAt()).isEqualTo(NOW);
        assertThat(view.version()).isEqualTo(2);

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.submission.referral-decided");
        assertThat(event.aggregateVersion()).isEqualTo(3L);
        assertThat(event.payload())
                .contains("\"submissionId\":\"" + SUBMISSION_ID + "\"")
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"decision\":\"APPROVED\"")
                .contains("\"decidedBy\":\"Chief Underwriter\"")
                .contains("\"decidedAt\":\"2026-10-22T12:00:00Z\"")
                .contains("\"notes\":\"Approved within authority\"")
                .contains("\"referralReason\":\"Aggregate limit above authority\"")
                .contains("\"version\":2");
    }

    @Test
    void decliningAPendingReferralAlsoDeclinesTheSubmission() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 1)))
                .thenReturn(Optional.of(referralSubmission("DECLINED", "DECLINED",
                        "Chief Underwriter", NOW, 2)));
        when(repository.decideReferral(eq(TENANT), eq(SUBMISSION_ID), eq("DECLINED"),
                eq("DECLINED"), eq("Chief Underwriter"), isNull(), eq("REFERRED"), eq(1),
                any(Instant.class))).thenReturn(1);

        UnderwritingSubmissionView view = service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest("DECLINED", "Chief Underwriter", "   "));

        assertThat(view.status()).isEqualTo("DECLINED");
        assertThat(view.referralDecision()).isEqualTo("DECLINED");
        assertThat(view.version()).isEqualTo(2);
        assertThat(onlyEvent().payload())
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"status\":\"DECLINED\"")
                .contains("\"decision\":\"DECLINED\"")
                .contains("\"notes\":\"Needs reinsurance sign-off\"");
    }

    @Test
    void aDecisionWithoutAPendingReferralIsAConflictWithoutAWriteOrEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(submission("REFERRED", 1)));

        assertThatThrownBy(() -> service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest("APPROVED", "Underwriter", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("REFERRAL_NOT_PENDING");
                    assertThat(api.meta())
                            .containsEntry("currentStatus", "REFERRED")
                            .doesNotContainKey("referralDecision");
                });

        verify(repository, never()).decideReferral(any(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void anInvalidDecisionBodyIsRejectedBeforeTheWrite() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 1)));

        assertThatThrownBy(() -> service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest("PENDING", "Underwriter", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_REFERRAL_DECISION"));

        verify(repository, never()).decideReferral(any(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentDecisionIsAConflictWithoutAnEvent() {
        when(repository.findById(TENANT, SUBMISSION_ID))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 1)))
                .thenReturn(Optional.of(referralSubmission("REFERRED", "PENDING", null, null, 2)));
        when(repository.decideReferral(eq(TENANT), eq(SUBMISSION_ID), eq("REFERRED"),
                eq("APPROVED"), eq("Underwriter"), isNull(), eq("REFERRED"), eq(1),
                any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest("APPROVED", "Underwriter", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta())
                            .containsEntry("currentVersion", 2)
                            .containsEntry("expectedVersion", 1);
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void referringAMissingSubmissionIsNotFound() {
        when(repository.findById(TENANT, SUBMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().refer(TENANT, SUBMISSION_ID,
                new ReferSubmissionRequest("Reason", "Authority", "Assignee", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("SUBMISSION_NOT_FOUND"));
        assertThatThrownBy(() -> service().decideReferral(TENANT, SUBMISSION_ID,
                new DecideReferralRequest("APPROVED", "Underwriter", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("SUBMISSION_NOT_FOUND"));

        verifyNoInteractions(outbox);
    }

    private OutboxEvent onlyEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static CreateUnderwritingSubmissionRequest request() {
        return new CreateUnderwritingSubmissionRequest(APPLICANT, " CGL_PRODUCTS ", EFFECTIVE,
                new BigDecimal("2500000"), " usd ",
                List.of(" ASBESTOS ", "PFAS"), "  Large manufacturing risk  ");
    }

    private static UnderwritingSubmission submission(String status, int version) {
        return new UnderwritingSubmission(SUBMISSION_ID, TENANT, "SUB-2026-0000001", APPLICANT,
                "CGL_PRODUCTS", EFFECTIVE, new BigDecimal("2500000.0000"), "USD",
                List.of("ASBESTOS", "PFAS"), status, "Large manufacturing risk", version,
                NOW, NOW);
    }

    private static UnderwritingSubmission referralSubmission(String status, String decision,
                                                             String decidedBy,
                                                             Instant decidedAt, int version) {
        return referralSubmission(status, decision, decidedBy, decidedAt,
                "Needs reinsurance sign-off", version);
    }

    private static UnderwritingSubmission referralSubmission(String status, String decision,
                                                             String decidedBy, Instant decidedAt,
                                                             String notes, int version) {
        return new UnderwritingSubmission(SUBMISSION_ID, TENANT, "SUB-2026-0000001", APPLICANT,
                "CGL_PRODUCTS", EFFECTIVE, new BigDecimal("2500000.0000"), "USD",
                List.of("ASBESTOS", "PFAS"), status, "Large manufacturing risk",
                "Aggregate limit above authority", "Chief Underwriting Officer",
                "Senior UW - Casualty", notes, decision, decidedBy, decidedAt, version,
                NOW, NOW);
    }
}
