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
class PolicyProgramServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PROGRAM_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID LEAD_POLICY_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private PolicyProgramRepository programRepository;

    @Mock
    private OutboxRepository outbox;

    private PolicyProgramService service() {
        return new PolicyProgramService(policyRepository, programRepository, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAProgramWithGeneratedNumberAndCreatedEvent() {
        when(programRepository.nextProgramNumber()).thenReturn("PRG-2026-0000001");

        ProgramView view = service().create(TENANT,
                new CreateProgramRequest("  Captive 2026 ", "captive", FROM, TO, LEAD_POLICY_ID));

        assertThat(view.programId()).isNotNull();
        assertThat(view.programNumber()).isEqualTo("PRG-2026-0000001");
        assertThat(view.name()).isEqualTo("Captive 2026");
        assertThat(view.programType()).isEqualTo("CAPTIVE");
        assertThat(view.effectiveFrom()).isEqualTo(FROM);
        assertThat(view.effectiveTo()).isEqualTo(TO);
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.leadPolicyId()).isEqualTo(LEAD_POLICY_ID);
        assertThat(view.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("policy.program.created");
        assertThat(event.aggregateType()).isEqualTo("Program");
        assertThat(event.aggregateId()).isEqualTo(view.programId());
        assertThat(event.aggregateVersion()).isEqualTo(1L);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.partitionKey()).isEqualTo(view.programId().toString());
        assertThat(event.payload())
                .contains("\"programId\":\"" + view.programId() + "\"")
                .contains("\"programNumber\":\"PRG-2026-0000001\"")
                .contains("\"programType\":\"CAPTIVE\"")
                .contains("\"effectiveFrom\":\"2026-01-01\"")
                .contains("\"effectiveTo\":\"2027-01-01\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"leadPolicyId\":\"" + LEAD_POLICY_ID + "\"")
                .contains("\"createdAt\":\"2026-10-20T12:00:00Z\"");
    }

    @Test
    void invalidCreateDoesNotConsumeAProgramNumberOrWriteAnEvent() {
        assertThatThrownBy(() -> service().create(TENANT,
                new CreateProgramRequest("Program", "SELF_INSURED", FROM, TO, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PROGRAM_TYPE"));

        verify(programRepository, never()).nextProgramNumber();
        verifyNoInteractions(outbox);
    }

    @Test
    void listsOnlyTheRequestedTenant() {
        when(programRepository.findAll(TENANT)).thenReturn(List.of(program()));

        assertThat(service().list(TENANT))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.programId()).isEqualTo(PROGRAM_ID);
                    assertThat(view.programNumber()).isEqualTo("PRG-2026-0000001");
                });
        verify(programRepository).findAll(TENANT);
    }

    @Test
    void getsAProgramByTenantAndId() {
        when(programRepository.findById(TENANT, PROGRAM_ID)).thenReturn(Optional.of(program()));

        assertThat(service().get(TENANT, PROGRAM_ID).programId()).isEqualTo(PROGRAM_ID);
    }

    @Test
    void aProgramFromAnotherTenantIsNotFound() {
        when(programRepository.findById(OTHER_TENANT, PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(OTHER_TENANT, PROGRAM_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("PROGRAM_NOT_FOUND");
                });
    }

    @Test
    void assignsTheProgramAndBumpsThePolicyVersion() {
        Policy before = policy(null, 2);
        Policy after = policy(PROGRAM_ID, 3);
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(programRepository.findById(TENANT, PROGRAM_ID)).thenReturn(Optional.of(program()));
        when(policyRepository.assignProgram(eq(TENANT), eq(POLICY_ID), eq(PROGRAM_ID), eq(2),
                any(Instant.class))).thenReturn(1);

        Policy result = service().assignProgram(TENANT, POLICY_ID,
                new AssignPolicyProgramRequest(PROGRAM_ID));

        assertThat(result.programId()).isEqualTo(PROGRAM_ID);
        assertThat(result.version()).isEqualTo(3);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("policy.policy.program-assigned");
        assertThat(event.aggregateType()).isEqualTo("Policy");
        assertThat(event.aggregateId()).isEqualTo(POLICY_ID);
        assertThat(event.aggregateVersion()).isEqualTo(4L);
        assertThat(event.payload())
                .contains("\"programId\":\"" + PROGRAM_ID + "\"")
                .contains("\"previousProgramId\":null")
                .contains("\"changedFields\":[\"programId\"]")
                .contains("\"version\":3");
    }

    @Test
    void clearsTheProgramAndEmitsANullProgramId() {
        Policy before = policy(PROGRAM_ID, 2);
        Policy after = policy(null, 3);
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(policyRepository.assignProgram(eq(TENANT), eq(POLICY_ID), eq(null), eq(2),
                any(Instant.class))).thenReturn(1);

        Policy result = service().assignProgram(TENANT, POLICY_ID,
                new AssignPolicyProgramRequest(null));

        assertThat(result.programId()).isNull();
        verify(programRepository, never()).findById(any(), any());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        assertThat(captor.getValue().payload())
                .contains("\"programId\":null")
                .contains("\"previousProgramId\":\"" + PROGRAM_ID + "\"");
    }

    @Test
    void assigningAnUnknownProgramIsANotFoundWithoutAPolicyWrite() {
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy(null, 2)));
        when(programRepository.findById(TENANT, PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignProgram(TENANT, POLICY_ID,
                new AssignPolicyProgramRequest(PROGRAM_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("PROGRAM_NOT_FOUND");
                });

        verify(policyRepository, never()).assignProgram(any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void assigningToAMissingPolicyIsANotFound() {
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignProgram(TENANT, POLICY_ID,
                new AssignPolicyProgramRequest(PROGRAM_ID)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("POLICY_NOT_FOUND"));

        verifyNoInteractions(programRepository, outbox);
    }

    @Test
    void aConcurrentPolicyChangeIsAConflictAndWritesNoEvent() {
        when(policyRepository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy(null, 2)))
                .thenReturn(Optional.of(policy(null, 3)));
        when(programRepository.findById(TENANT, PROGRAM_ID)).thenReturn(Optional.of(program()));
        when(policyRepository.assignProgram(eq(TENANT), eq(POLICY_ID), eq(PROGRAM_ID), eq(2),
                any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service().assignProgram(TENANT, POLICY_ID,
                new AssignPolicyProgramRequest(PROGRAM_ID)))
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

    private static PolicyProgram program() {
        return new PolicyProgram(PROGRAM_ID, TENANT, "PRG-2026-0000001", "Captive 2026",
                "CAPTIVE", FROM, TO, "ACTIVE", LEAD_POLICY_ID, CREATED);
    }

    private static Policy policy(UUID programId, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, "DRAFT",
                FROM, TO, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED, null,
                programId);
    }
}
