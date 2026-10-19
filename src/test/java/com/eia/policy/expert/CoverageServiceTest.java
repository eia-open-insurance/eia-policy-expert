package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoverageServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LIMIT_ID = UUID.fromString("88888888-8888-7888-8888-888888888888");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private MutationGuard guard;
    @Mock
    private CoverageRepository coverageRepository;
    @Mock
    private PolicyVersionService versionService;
    @Mock
    private VersionIssuer versionIssuer;
    @Mock
    private EndorsementFactory endorsementFactory;

    @Test
    void createsACoverageWithItsLimitScheduleAndInitialStateBeforeIssue() {
        Policy policy = policy("DRAFT", 0);
        when(guard.requirePolicy(TENANT, POLICY_ID, "\"0\"")).thenReturn(policy);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(coverageRepository.coverageCodeExists(TENANT, POLICY_ID, "GL_PRODUCTS"))
                .thenReturn(false);
        when(coverageRepository.nextCoverageSequence(TENANT, POLICY_ID)).thenReturn(1);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(false);
        when(policyRepository.touch(TENANT, POLICY_ID, 0)).thenReturn(1);

        CoverageResult result = service().create(TENANT, POLICY_ID, request(), "\"0\"", null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.coverage().coverage().coverageCode()).isEqualTo("GL_PRODUCTS");
        assertThat(result.coverage().limit().perOccurrenceAmount())
                .isEqualByComparingTo("2000000");
        assertThat(result.coverage().limit().aggregateAmount()).isEqualByComparingTo("5000000");
        assertThat(result.coverage().limit().deductibleAmount()).isEqualByComparingTo("5000");
        assertThat(result.coverage().limit().coinsurancePct()).isEqualByComparingTo("0.10");
        assertThat(result.coverage().state().remainingAmount()).isEqualByComparingTo("5000000");
        assertThat(result.coverage().state().originalAmount()).isEqualByComparingTo("5000000");
        verify(coverageRepository).insertCoverage(any());
        verify(coverageRepository).insertLimit(any());
        verify(coverageRepository).insertState(any());
        verify(versionIssuer, never()).issue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createsACoverageAsAnEndorsementAfterIssue() {
        Policy policy = policy("IN_FORCE", 4);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(coverageRepository.coverageCodeExists(TENANT, POLICY_ID, "GL_PRODUCTS"))
                .thenReturn(false);
        when(coverageRepository.nextCoverageSequence(TENANT, POLICY_ID)).thenReturn(2);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version());
        when(policyRepository.touch(TENANT, POLICY_ID, 4)).thenReturn(1);

        service().create(TENANT, POLICY_ID, request(), null, null);

        verify(endorsementFactory).create(any(), eq(policy), any(), any(), eq("COVERAGE"), any(),
                any(), any(), any(), eq("BROADEN"), any(), any(), any(), any(), any(), any(),
                eq(false));
        verify(versionIssuer).issue(eq(policy), any(), eq("ENDORSEMENT"), any(), any(), any(),
                any(), any());
    }

    @Test
    void rejectsADuplicateActiveCoverageCode() {
        Policy policy = policy("IN_FORCE", 4);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(coverageRepository.coverageCodeExists(TENANT, POLICY_ID, "GL_PRODUCTS"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().create(TENANT, POLICY_ID, request(), null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_COVERAGE_CODE");
                });
        verify(coverageRepository, never()).insertCoverage(any());
    }

    @Test
    void appliesAnErosionFactAndReducesTheRemainingAggregate() {
        Policy policy = policy("IN_FORCE", 6);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(coverageRepository.findLimit(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(limit()));
        when(coverageRepository.findState(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(state("5000000", "0", "0")));
        when(coverageRepository.erosionFactExists(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(false);
        when(coverageRepository.chainHasNewerFact(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(false);
        when(coverageRepository.sumLatestFacts(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(new CoverageRepository.ErosionTotals(
                        new BigDecimal("250000"), new BigDecimal("50000"), BigDecimal.ZERO, 1L));
        when(coverageRepository.updateState(any(), eq(0))).thenReturn(1);
        when(coverageRepository.findState(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(state("5000000", "0", "0")),
                        Optional.of(state("5000000", "0", "0")),
                        Optional.of(state("4700000", "250000", "50000")));

        ErosionResult result = service().applyErosion(TENANT, POLICY_ID, erosion(), null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.state().remainingAmount()).isEqualByComparingTo("4700000");
        assertThat(result.state().incurredAmount()).isEqualByComparingTo("300000");
        ArgumentCaptor<PolicyLimitState> captor = ArgumentCaptor.forClass(PolicyLimitState.class);
        verify(coverageRepository).updateState(captor.capture(), eq(0));
        assertThat(captor.getValue().erodedPct()).isEqualByComparingTo("0.060000");
    }

    @Test
    void rejectsAnErosionFactThatExceedsTheLimit() {
        Policy policy = policy("IN_FORCE", 6);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(coverageRepository.findLimit(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(limit()));
        when(coverageRepository.findState(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(state("5000000", "0", "0")));
        when(coverageRepository.erosionFactExists(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(false);
        when(coverageRepository.chainHasNewerFact(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(false);
        when(coverageRepository.sumLatestFacts(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(new CoverageRepository.ErosionTotals(
                        new BigDecimal("6000000"), BigDecimal.ZERO, BigDecimal.ZERO, 1L));

        assertThatThrownBy(() -> service().applyErosion(TENANT, POLICY_ID, erosion(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("LIMIT_EXCEEDED");
                    assertThat(api.meta()).containsEntry("availableAmount", "5000000.0000");
                });
        verify(coverageRepository, never()).updateState(any(), anyInt());
    }

    @Test
    void replaysTheSameErosionFactWithoutDoubleCounting() {
        Policy policy = policy("IN_FORCE", 6);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(coverageRepository.findLimit(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(limit()));
        when(coverageRepository.findState(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(state("4700000", "250000", "50000")));
        when(coverageRepository.erosionFactExists(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(true);

        ErosionResult result = service().applyErosion(TENANT, POLICY_ID, erosion(), null);

        assertThat(result.replayed()).isTrue();
        assertThat(result.state().remainingAmount()).isEqualByComparingTo("4700000");
        verify(coverageRepository, never()).insertErosionFact(any(), any(), any(), any(), any(),
                any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAStaleErosionFact() {
        Policy policy = policy("IN_FORCE", 6);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(coverageRepository.findLimit(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(limit()));
        when(coverageRepository.findState(TENANT, POLICY_ID, LIMIT_ID))
                .thenReturn(Optional.of(state("5000000", "0", "0")));
        when(coverageRepository.erosionFactExists(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(false);
        when(coverageRepository.chainHasNewerFact(LIMIT_ID, CLAIM, ALLOCATION, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service().applyErosion(TENANT, POLICY_ID, erosion(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("EROSION_STALE_VERSION"));
    }

    private CoverageService service() {
        return new CoverageService(policyRepository, guard, coverageRepository, versionService,
                versionIssuer, endorsementFactory, new CommandIdempotency(policyRepository));
    }

    private static final UUID CLAIM = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID ALLOCATION = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");

    private static CreateCoverageRequest request() {
        return new CreateCoverageRequest("GL_PRODUCTS", "Products liability", "A", "OCCURRENCE",
                FROM, TO, new BigDecimal("0.10"),
                new CreateCoverageRequest.LimitSpec(new BigDecimal("2000000"),
                        new BigDecimal("5000000"), null, new BigDecimal("1000000"),
                        new BigDecimal("5000"), new BigDecimal("0.10"), "USD", null));
    }

    private static ErosionRequest erosion() {
        return new ErosionRequest(LIMIT_ID, CLAIM, ALLOCATION, 1L, new BigDecimal("250000"),
                new BigDecimal("50000"), BigDecimal.ZERO, Instant.parse("2026-10-19T12:00:00Z"));
    }

    private static Policy policy(String status, int version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                version, now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyVersion(UUID.randomUUID(), TENANT, POLICY_ID, 1, "POL-2026-0000001-01",
                "ISSUE", "ISSUED", FROM, null, now, null, null, null, null, null, "Issued",
                null, "hash", now);
    }

    private static PolicyLimit limit() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyLimit(LIMIT_ID, TENANT, POLICY_ID,
                UUID.fromString("77777777-7777-7777-8777-777777777777"),
                new BigDecimal("2000000"), new BigDecimal("5000000"), "PER_COVERAGE",
                new BigDecimal("1000000"), new BigDecimal("5000"), new BigDecimal("0.10"),
                "USD", "PAID_PLUS_RESERVED", FROM, TO, 1, now);
    }

    private static PolicyLimitState state(String remaining, String paid, String reserved) {
        BigDecimal incurred = new BigDecimal(paid).add(new BigDecimal(reserved));
        return new PolicyLimitState(UUID.randomUUID(), TENANT, POLICY_ID, LIMIT_ID,
                Instant.parse("2026-10-19T12:00:00Z"), new BigDecimal("5000000"),
                new BigDecimal(paid), new BigDecimal(reserved), incurred, BigDecimal.ZERO,
                new BigDecimal(remaining), new BigDecimal("0.060000"), BigDecimal.ZERO, 1L,
                Instant.parse("2026-10-19T12:00:00Z"), "CURRENT", 0);
    }
}
