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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegalEntityServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LEGAL_ENTITY_ID =
            UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-20T12:00:00Z");

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private LegalEntityRepository legalEntityRepository;

    @Mock
    private OutboxRepository outbox;

    private LegalEntityService service() {
        return new LegalEntityService(policyRepository, legalEntityRepository, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsALegalEntityWithNormalizedFieldsAndACreatedEvent() {
        LegalEntityView view = service().create(TENANT, new CreateLegalEntityRequest(
                "  le-001  ", "  Acme Insurance  ", " insurer ", " us ",
                "  NAIC  ", "  LIC-42  "));

        assertThat(view.legalEntityId()).isNotNull();
        assertThat(view.code()).isEqualTo("LE-001");
        assertThat(view.name()).isEqualTo("Acme Insurance");
        assertThat(view.entityType()).isEqualTo("INSURER");
        assertThat(view.jurisdiction()).isEqualTo("US");
        assertThat(view.regulator()).isEqualTo("NAIC");
        assertThat(view.licenseNumber()).isEqualTo("LIC-42");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<LegalEntity> inserted = ArgumentCaptor.forClass(LegalEntity.class);
        verify(legalEntityRepository).insert(inserted.capture());
        assertThat(inserted.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(inserted.getValue().status()).isEqualTo("ACTIVE");

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.legal-entity.created");
        assertThat(event.aggregateType()).isEqualTo("LegalEntity");
        assertThat(event.aggregateId()).isEqualTo(view.legalEntityId());
        assertThat(event.aggregateVersion()).isEqualTo(1L);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.partitionKey()).isEqualTo(view.legalEntityId().toString());
        assertThat(event.payload())
                .contains("\"legalEntityId\":\"" + view.legalEntityId() + "\"")
                .contains("\"code\":\"LE-001\"")
                .contains("\"name\":\"Acme Insurance\"")
                .contains("\"entityType\":\"INSURER\"")
                .contains("\"jurisdiction\":\"US\"")
                .contains("\"regulator\":\"NAIC\"")
                .contains("\"licenseNumber\":\"LIC-42\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"createdAt\":\"2026-10-21T12:00:00Z\"");
    }

    @Test
    void blankOptionalFieldsAreEmittedAsJsonNull() {
        LegalEntityView view = service().create(TENANT, new CreateLegalEntityRequest(
                "LE-002", "Pool Co", "POOL", "GB", null, null));

        OutboxEvent event = onlyEvent();
        assertThat(event.payload())
                .contains("\"regulator\":null")
                .contains("\"licenseNumber\":null");
        assertThat(view.regulator()).isNull();
        assertThat(view.licenseNumber()).isNull();
    }

    @Test
    void duplicateCodeIsAConflictWithoutAnEvent() {
        doThrow(new DuplicateKeyException("duplicate")).when(legalEntityRepository)
                .insert(any(LegalEntity.class));

        assertThatThrownBy(() -> service().create(TENANT, new CreateLegalEntityRequest(
                "LE-001", "Acme", "INSURER", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_LEGAL_ENTITY_CODE");
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void invalidCreateDoesNotWriteARowOrAnEvent() {
        assertThatThrownBy(() -> service().create(TENANT, new CreateLegalEntityRequest(
                "LE-001", "Acme", "SELF_INSURED", "US", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_LEGAL_ENTITY_TYPE"));

        verifyNoInteractions(legalEntityRepository, outbox);
    }

    @Test
    void listsOnlyTheRequestedTenant() {
        when(legalEntityRepository.findAll(TENANT)).thenReturn(List.of(legalEntity()));

        assertThat(service().list(TENANT))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.legalEntityId()).isEqualTo(LEGAL_ENTITY_ID);
                    assertThat(view.code()).isEqualTo("LE-001");
                });
        verify(legalEntityRepository).findAll(TENANT);
    }

    @Test
    void getsALegalEntityByTenantAndId() {
        when(legalEntityRepository.findById(TENANT, LEGAL_ENTITY_ID))
                .thenReturn(Optional.of(legalEntity()));

        assertThat(service().get(TENANT, LEGAL_ENTITY_ID).legalEntityId())
                .isEqualTo(LEGAL_ENTITY_ID);
    }

    @Test
    void aLegalEntityFromAnotherTenantIsNotFound() {
        when(legalEntityRepository.findById(OTHER_TENANT, LEGAL_ENTITY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(OTHER_TENANT, LEGAL_ENTITY_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("LEGAL_ENTITY_NOT_FOUND");
                });
    }

    @Test
    void assignsTheLegalEntityAndBumpsThePolicyVersion() {
        Policy before = policy(null, 2);
        Policy after = policy(LEGAL_ENTITY_ID, 3);
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(legalEntityRepository.findById(TENANT, LEGAL_ENTITY_ID))
                .thenReturn(Optional.of(legalEntity()));
        when(policyRepository.assignLegalEntity(eq(TENANT), eq(POLICY_ID),
                eq(LEGAL_ENTITY_ID), eq(2), any(Instant.class))).thenReturn(1);

        Policy result = service().assignLegalEntity(TENANT, POLICY_ID,
                new AssignPolicyLegalEntityRequest(LEGAL_ENTITY_ID));

        assertThat(result.legalEntityId()).isEqualTo(LEGAL_ENTITY_ID);
        assertThat(result.version()).isEqualTo(3);

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("policy.policy.legal-entity-assigned");
        assertThat(event.aggregateType()).isEqualTo("Policy");
        assertThat(event.aggregateId()).isEqualTo(POLICY_ID);
        assertThat(event.aggregateVersion()).isEqualTo(4L);
        assertThat(event.payload())
                .contains("\"legalEntityId\":\"" + LEGAL_ENTITY_ID + "\"")
                .contains("\"previousLegalEntityId\":null")
                .contains("\"changedFields\":[\"legalEntityId\"]")
                .contains("\"version\":3");
    }

    @Test
    void clearsTheLegalEntityAndEmitsANullLegalEntityId() {
        Policy before = policy(LEGAL_ENTITY_ID, 2);
        Policy after = policy(null, 3);
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(policyRepository.assignLegalEntity(eq(TENANT), eq(POLICY_ID), eq(null), eq(2),
                any(Instant.class))).thenReturn(1);

        Policy result = service().assignLegalEntity(TENANT, POLICY_ID,
                new AssignPolicyLegalEntityRequest(null));

        assertThat(result.legalEntityId()).isNull();
        verify(legalEntityRepository, never()).findById(any(), any());

        OutboxEvent event = onlyEvent();
        assertThat(event.payload())
                .contains("\"legalEntityId\":null")
                .contains("\"previousLegalEntityId\":\"" + LEGAL_ENTITY_ID + "\"");
    }

    @Test
    void assigningAnUnknownLegalEntityIsANotFoundWithoutAPolicyWrite() {
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy(null, 2)));
        when(legalEntityRepository.findById(TENANT, LEGAL_ENTITY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignLegalEntity(TENANT, POLICY_ID,
                new AssignPolicyLegalEntityRequest(LEGAL_ENTITY_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("LEGAL_ENTITY_NOT_FOUND");
                });

        verify(policyRepository, never()).assignLegalEntity(any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void assigningToAMissingPolicyIsANotFound() {
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignLegalEntity(TENANT, POLICY_ID,
                new AssignPolicyLegalEntityRequest(LEGAL_ENTITY_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("POLICY_NOT_FOUND"));

        verifyNoInteractions(legalEntityRepository, outbox);
    }

    @Test
    void aConcurrentPolicyChangeIsAConflictAndWritesNoEvent() {
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy(null, 2)))
                .thenReturn(Optional.of(policy(null, 3)));
        when(legalEntityRepository.findById(TENANT, LEGAL_ENTITY_ID))
                .thenReturn(Optional.of(legalEntity()));
        when(policyRepository.assignLegalEntity(eq(TENANT), eq(POLICY_ID),
                eq(LEGAL_ENTITY_ID), eq(2), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service().assignLegalEntity(TENANT, POLICY_ID,
                new AssignPolicyLegalEntityRequest(LEGAL_ENTITY_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta())
                            .containsEntry("currentVersion", 3)
                            .containsEntry("expectedVersion", 2);
                });

        verifyNoInteractions(outbox);
    }

    private OutboxEvent onlyEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static LegalEntity legalEntity() {
        return new LegalEntity(LEGAL_ENTITY_ID, TENANT, "LE-001", "Acme Insurance",
                "INSURER", "US", "NAIC", "LIC-42", "ACTIVE", CREATED);
    }

    private static Policy policy(UUID legalEntityId, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, "DRAFT",
                FROM, TO, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED, null,
                null, legalEntityId);
    }
}
