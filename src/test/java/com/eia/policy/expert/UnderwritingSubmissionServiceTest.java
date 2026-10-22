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
    private OutboxRepository outbox;

    private UnderwritingSubmissionService service() {
        return new UnderwritingSubmissionService(repository, numberAllocator, policyRepository,
                outbox, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void invalidCreateDoesNotAllocateAValidateOrWriteAnEvent() {
        assertThatThrownBy(() -> service().create(TENANT,
                new CreateUnderwritingSubmissionRequest(APPLICANT, "P".repeat(65), EFFECTIVE,
                        BigDecimal.ONE, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_PRODUCT_CODE"));

        verifyNoInteractions(repository, numberAllocator, outbox);
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
}
