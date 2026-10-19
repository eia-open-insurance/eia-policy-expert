package com.eia.policy.expert;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyPartyServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private MutationGuard guard;
    @Mock
    private PolicyPartyRepository partyRepository;
    @Mock
    private PolicyVersionService versionService;
    @Mock
    private VersionIssuer versionIssuer;
    @Mock
    private EndorsementFactory endorsementFactory;

    @Test
    void addsANamedInsuredBeforeIssueWithoutIssuingAVersion() {
        Policy policy = policy("DRAFT", 0);
        when(guard.requirePolicy(TENANT, POLICY_ID, "\"0\"")).thenReturn(policy);
        when(partyRepository.insuredExists(TENANT, POLICY_ID, PARTY, "NAMED_INSURED"))
                .thenReturn(false);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(false);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(policyRepository.touch(TENANT, POLICY_ID, 0)).thenReturn(1);

        InsuredResult result = service().createInsured(TENANT, POLICY_ID, insuredRequest(),
                "\"0\"", null);

        assertThat(result.insured().insuredRole()).isEqualTo("NAMED_INSURED");
        assertThat(result.insured().partyId()).isEqualTo(PARTY);
        assertThat(result.replayed()).isFalse();
        verify(partyRepository).insertInsured(any());
        verify(versionIssuer, never()).issue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addingAnInsuredAfterIssueIssuesAnEndorsementAndVersion() {
        Policy policy = policy("IN_FORCE", 5);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(partyRepository.insuredExists(TENANT, POLICY_ID, PARTY, "ADDITIONAL_INSURED"))
                .thenReturn(false);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(true);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version());
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(policyRepository.touch(TENANT, POLICY_ID, 5)).thenReturn(1);

        CreateInsuredRequest request = new CreateInsuredRequest(PARTY, "ADDITIONAL_INSURED",
                "Acme Chemical Corporation", "CERT-1", null, FROM.plusMonths(6), TO);
        service().createInsured(TENANT, POLICY_ID, request, null, null);

        verify(endorsementFactory).create(any(), eq(policy), any(), any(), eq("PARTY"), any(),
                any(), any(), any(), eq("NONE"), any(), any(), any(), any(), any(), any(), eq(false));
        verify(versionIssuer).issue(eq(policy), any(), eq("ENDORSEMENT"), any(), any(), any(),
                any(), any());
    }

    @Test
    void rejectsADuplicateInsuredRole() {
        Policy policy = policy("IN_FORCE", 5);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(partyRepository.insuredExists(TENANT, POLICY_ID, PARTY, "NAMED_INSURED"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().createInsured(TENANT, POLICY_ID, insuredRequest(),
                null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_INSURED");
                });
        verify(partyRepository, never()).insertInsured(any());
    }

    @Test
    void rejectsInsuredDatesOutsideThePolicyTerm() {
        Policy policy = policy("DRAFT", 0);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);

        CreateInsuredRequest request = new CreateInsuredRequest(PARTY, "NAMED_INSURED",
                "Acme", null, null, FROM.minusDays(1), TO);

        assertThatThrownBy(() -> service().createInsured(TENANT, POLICY_ID, request, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INSURED_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void addsALocationWithADuplicateCodeConflict() {
        Policy policy = policy("DRAFT", 0);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(partyRepository.locationCodeExists(TENANT, POLICY_ID, "LOC-001")).thenReturn(true);

        assertThatThrownBy(() -> service().createLocation(TENANT, POLICY_ID, locationRequest(),
                null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("DUPLICATE_LOCATION_CODE"));
    }

    @Test
    void addsALocationAndNormalizesItsCodes() {
        Policy policy = policy("DRAFT", 0);
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy);
        when(partyRepository.locationCodeExists(TENANT, POLICY_ID, "LOC-002")).thenReturn(false);
        when(versionService.isIssued(TENANT, POLICY_ID)).thenReturn(false);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy));
        when(policyRepository.touch(TENANT, POLICY_ID, 0)).thenReturn(1);

        LocationResult result = service().createLocation(TENANT, POLICY_ID,
                new CreateLocationRequest("LOC-002", "1 Main St", null, "Akron", "us-oh", "44311",
                        "usa", FROM, TO), null, null);

        assertThat(result.location().locationCode()).isEqualTo("LOC-002");
        assertThat(result.location().stateRegion()).isEqualTo("US-OH");
        assertThat(result.location().country()).isEqualTo("USA");
        verify(partyRepository).insertLocation(any());
    }

    private PolicyPartyService service() {
        return new PolicyPartyService(policyRepository, guard, partyRepository, versionService,
                versionIssuer, endorsementFactory, new CommandIdempotency(policyRepository));
    }

    private static CreateInsuredRequest insuredRequest() {
        return new CreateInsuredRequest(PARTY, "NAMED_INSURED", "Acme Chemical Corporation",
                null, null, FROM, TO);
    }

    private static CreateLocationRequest locationRequest() {
        return new CreateLocationRequest("LOC-001", "1 Main St", null, "Akron", "US-OH", "44311",
                "USA", FROM, TO);
    }

    private static Policy policy(String status, int version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, status, FROM, TO,
                new BigDecimal("125000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                version, now, now);
    }

    private static PolicyVersion version() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(UUID.randomUUID(), TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "ENDORSEMENT", "ISSUED", LocalDate.of(2026, 6, 1), null, now, null, null, null,
                null, null, "Endorsement", null, "hash", now);
    }
}
