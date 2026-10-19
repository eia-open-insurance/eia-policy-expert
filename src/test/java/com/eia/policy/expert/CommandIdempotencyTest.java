package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommandIdempotencyTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RESOURCE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Test
    void executesTheCommandWhenNoKeyIsSupplied() {
        PolicyRepository repository = org.mockito.Mockito.mock(PolicyRepository.class);
        CommandIdempotency idempotency = new CommandIdempotency(repository);

        CommandResult<String> result = idempotency.run(TENANT, POLICY_ID, "coverage", null,
                "hash", RESOURCE_ID, id -> "created:" + id, id -> "loaded:" + id);

        assertThat(result.replayed()).isFalse();
        assertThat(result.value()).isEqualTo("created:" + RESOURCE_ID);
        verifyNoInteractions(repository);
    }

    @Test
    void replaysTheStoredResourceForTheSameRequestHash() {
        PolicyRepository repository = org.mockito.Mockito.mock(PolicyRepository.class);
        when(repository.findCommandIdempotency(TENANT, "coverage", "key-1"))
                .thenReturn(Optional.of(new CommandIdempotencyRecord(RESOURCE_ID, "hash")));
        CommandIdempotency idempotency = new CommandIdempotency(repository);

        CommandResult<String> result = idempotency.run(TENANT, POLICY_ID, "coverage", "key-1",
                "hash", UUID.randomUUID(), id -> "created:" + id, id -> "loaded:" + id);

        assertThat(result.replayed()).isTrue();
        assertThat(result.value()).isEqualTo("loaded:" + RESOURCE_ID);
    }

    @Test
    void rejectsTheSameKeyForADifferentRequest() {
        PolicyRepository repository = org.mockito.Mockito.mock(PolicyRepository.class);
        when(repository.findCommandIdempotency(TENANT, "coverage", "key-1"))
                .thenReturn(Optional.of(new CommandIdempotencyRecord(RESOURCE_ID, "other")));
        CommandIdempotency idempotency = new CommandIdempotency(repository);

        assertThatThrownBy(() -> idempotency.run(TENANT, POLICY_ID, "coverage", "key-1",
                "hash", UUID.randomUUID(), id -> "created:" + id, id -> "loaded:" + id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(api.meta()).containsEntry("existingResourceId",
                            RESOURCE_ID.toString());
                });
    }

    @Test
    void replaysWhenAConcurrentRequestWonTheReservation() {
        PolicyRepository repository = org.mockito.Mockito.mock(PolicyRepository.class);
        when(repository.findCommandIdempotency(TENANT, "coverage", "key-1"))
                .thenReturn(Optional.empty(),
                        Optional.of(new CommandIdempotencyRecord(RESOURCE_ID, "hash")));
        when(repository.insertCommandIdempotencyIfAbsent(TENANT, "coverage", "key-1", "hash",
                POLICY_ID, RESOURCE_ID)).thenReturn(false);
        CommandIdempotency idempotency = new CommandIdempotency(repository);

        CommandResult<String> result = idempotency.run(TENANT, POLICY_ID, "coverage", "key-1",
                "hash", RESOURCE_ID, id -> "created:" + id, id -> "loaded:" + id);

        assertThat(result.replayed()).isTrue();
        assertThat(result.value()).isEqualTo("loaded:" + RESOURCE_ID);
    }

    @Test
    void reservesTheKeyBeforeRunningTheCommand() {
        PolicyRepository repository = org.mockito.Mockito.mock(PolicyRepository.class);
        when(repository.findCommandIdempotency(TENANT, "coverage", "key-1"))
                .thenReturn(Optional.empty());
        when(repository.insertCommandIdempotencyIfAbsent(any(), anyString(), anyString(), anyString(),
                any(), any())).thenReturn(true);
        CommandIdempotency idempotency = new CommandIdempotency(repository);

        idempotency.run(TENANT, POLICY_ID, "coverage", "key-1", "hash", RESOURCE_ID,
                id -> "created:" + id, id -> "loaded:" + id);

        verify(repository).insertCommandIdempotencyIfAbsent(TENANT, "coverage", "key-1", "hash",
                POLICY_ID, RESOURCE_ID);
        verify(repository, never()).findCommandIdempotency(TENANT, "coverage", "other");
    }
}
