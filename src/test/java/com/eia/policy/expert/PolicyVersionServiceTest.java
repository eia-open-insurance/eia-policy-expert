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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyVersionServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private PolicyVersionRepository versionRepository;

    @Mock
    private PolicySnapshotLoader snapshotLoader;

    @Test
    void createsTheInitialDraftVersionWithACanonicalHash() {
        Policy policy = policy();

        PolicyVersion version = service().createInitialVersion(policy, VERSION_ID);

        assertThat(version.status()).isEqualTo("DRAFT");
        assertThat(version.versionNumber()).isEqualTo(1);
        assertThat(version.policyVersionNumber()).isEqualTo("POL-2026-0000001-01");
        assertThat(version.changeType()).isEqualTo("BIND");
        assertThat(version.immutableHash()).hasSize(64);
        verify(versionRepository).insert(version);
    }

    @Test
    void currentVersionIsVerifiedAgainstTheLiveSnapshot() {
        Policy policy = policy();
        PolicyVersion issued = issuedVersion(policy, "hash-placeholder");
        String expectedHash = PolicyVersionHasher.hash(policy, issued,
                new PolicySnapshot(List.of(), List.of(), List.of(), List.of(), List.of()));
        PolicyVersion verified = issued.withHash(expectedHash);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(versionRepository.findIssued(TENANT, POLICY_ID)).thenReturn(Optional.of(verified));
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());

        CurrentVersion current = service().current(TENANT, POLICY_ID);

        assertThat(current.current()).isTrue();
        assertThat(current.hashVerified()).isTrue();
        assertThat(current.version().id()).isEqualTo(VERSION_ID);
    }

    @Test
    void rejectsACurrentVersionWhoseHashDoesNotMatch() {
        Policy policy = policy();
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(versionRepository.findIssued(TENANT, POLICY_ID))
                .thenReturn(Optional.of(issuedVersion(policy, "tampered")));
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());

        assertThatThrownBy(() -> service().current(TENANT, POLICY_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("VERSION_HASH_MISMATCH");
                });
    }

    @Test
    void fallsBackToTheLatestDraftBeforeIssue() {
        Policy policy = policy();
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(versionRepository.findIssued(TENANT, POLICY_ID)).thenReturn(Optional.empty());
        when(versionRepository.findLatest(TENANT, POLICY_ID))
                .thenReturn(Optional.of(draftVersion(policy)));

        CurrentVersion current = service().current(TENANT, POLICY_ID);

        assertThat(current.current()).isTrue();
        assertThat(current.hashVerified()).isFalse();
        assertThat(current.version().status()).isEqualTo("DRAFT");
    }

    @Test
    void reportsAMissingPolicyAsNotFound() {
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().current(TENANT, POLICY_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("POLICY_NOT_FOUND"));
    }

    @Test
    void issuingADraftRecomputesTheHashAndFlipsTheStatus() {
        Policy policy = policy();
        PolicyVersion draft = draftVersion(policy);
        when(versionRepository.findDraft(TENANT, POLICY_ID)).thenReturn(Optional.of(draft));
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());
        when(versionRepository.issueDraft(eq(TENANT), eq(POLICY_ID), eq(VERSION_ID), any(),
                any(), any(), anyString())).thenReturn(1);

        PolicyVersion issued = service().issuePolicyVersion(policy, null);

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.immutableHash()).hasSize(64);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(versionRepository).issueDraft(eq(TENANT), eq(POLICY_ID), eq(VERSION_ID), any(),
                any(), any(), hash.capture());
        assertThat(hash.getValue()).isEqualTo(issued.immutableHash());
    }

    @Test
    void issuingTwiceIsAConflict() {
        Policy policy = policy();
        PolicyVersion draft = draftVersion(policy);
        when(versionRepository.findDraft(TENANT, POLICY_ID)).thenReturn(Optional.of(draft));
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());
        when(versionRepository.issueDraft(eq(TENANT), eq(POLICY_ID), eq(VERSION_ID), any(),
                any(), any(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service().issuePolicyVersion(policy, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("VERSION_ALREADY_ISSUED"));
        verify(versionRepository, never()).insert(any());
    }

    @Test
    void issuingAfterAnAuditClosesTheCurrentIssuedVersionFirst() {
        Policy policy = policy();
        PolicyVersion current = issuedVersion(policy, "a".repeat(64));
        when(versionRepository.findDraft(TENANT, POLICY_ID)).thenReturn(Optional.empty());
        when(versionRepository.findIssued(TENANT, POLICY_ID)).thenReturn(Optional.of(current));
        when(versionRepository.nextVersionNumber(TENANT, POLICY_ID)).thenReturn(2);
        when(snapshotLoader.load(TENANT, POLICY_ID)).thenReturn(PolicySnapshot.empty());
        when(versionRepository.issueDraft(eq(TENANT), eq(POLICY_ID), any(), any(), any(), any(),
                anyString())).thenReturn(1);

        PolicyVersion issued = service().issuePolicyVersion(policy, null);

        assertThat(issued.changeType()).isEqualTo("ISSUE");
        assertThat(issued.status()).isEqualTo("ISSUED");
        verify(versionRepository).supersede(TENANT, POLICY_ID, current.id(),
                current.effectiveFrom());
    }

    @Test
    void listingVersionsRejectsAnUnknownPolicy() {
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().list(TENANT, POLICY_ID, 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("POLICY_NOT_FOUND"));
        verify(versionRepository, never()).findAll(any(), any());
    }

    @Test
    void listingVersionsPagesTheHistory() {
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy()));
        when(versionRepository.findAll(TENANT, POLICY_ID)).thenReturn(
                List.of(issuedVersion(policy(), "a"), draftVersion(policy())));

        ItemPage<PolicyVersion> page = service().list(TENANT, POLICY_ID, 0, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page._links().next()).isNotNull();
        assertThat(page._links().prev()).isNull();
    }

    private PolicyVersionService service() {
        return new PolicyVersionService(policyRepository, versionRepository, snapshotLoader);
    }

    private static Policy policy() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "ISSUED", FROM, TO,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                4, now, now);
    }

    private static PolicyVersion issuedVersion(Policy policy, String hash) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 1, "POL-2026-0000001-01",
                "ISSUE", "ISSUED", FROM, null, now, null, null, null, null, null,
                "Policy issued", null, hash, now);
    }

    private static PolicyVersion draftVersion(Policy policy) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 1, "POL-2026-0000001-01",
                "BIND", "DRAFT", FROM, null, null, null, null, null, null, null,
                "Policy created", null, "", now);
    }
}
