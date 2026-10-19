package com.eia.policy.expert;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Idempotent command wrapper for the second-slice mutations.
 *
 * <p>The reservation is written in the same transaction as the command, so a
 * failed command leaves no key behind. A same-key replay with an identical
 * canonical request returns the stored resource with {@code replayed=true};
 * reusing the key for a different request is 409 {@code IDEMPOTENCY_CONFLICT},
 * matching the create-policy behaviour hardened in the first slice.
 */
@Component
public class CommandIdempotency {
    private final PolicyRepository repository;

    public CommandIdempotency(PolicyRepository repository) {
        this.repository = repository;
    }

    public <T> CommandResult<T> run(UUID tenantId,
                                    UUID policyId,
                                    String scope,
                                    String rawKey,
                                    String requestHash,
                                    UUID resourceId,
                                    Function<UUID, T> action,
                                    Function<UUID, T> loader) {
        String key = IdempotencyKeys.normalize(rawKey);
        if (key == null) {
            return new CommandResult<>(action.apply(resourceId), false);
        }
        Optional<CommandIdempotencyRecord> existing =
                repository.findCommandIdempotency(tenantId, scope, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, loader);
        }
        if (!repository.insertCommandIdempotencyIfAbsent(tenantId, scope, key, requestHash,
                policyId, resourceId)) {
            CommandIdempotencyRecord raced = repository
                    .findCommandIdempotency(tenantId, scope, key)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_ERROR", "The idempotency reservation could not be resolved."));
            return replay(raced, requestHash, loader);
        }
        return new CommandResult<>(action.apply(resourceId), false);
    }

    private <T> CommandResult<T> replay(CommandIdempotencyRecord record, String requestHash,
                                        Function<UUID, T> loader) {
        if (!record.requestHash().equals(requestHash)) {
            String message = "Idempotency-Key was already used with a different request.";
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message,
                    java.util.List.of(new ApiException.FieldIssue(
                            "Idempotency-Key", "IDEMPOTENCY_CONFLICT", message)),
                    Map.of("existingResourceId", record.resourceId().toString()));
        }
        T value = loader.apply(record.resourceId());
        if (value == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The stored idempotency record is inconsistent.");
        }
        return new CommandResult<>(value, true);
    }
}
