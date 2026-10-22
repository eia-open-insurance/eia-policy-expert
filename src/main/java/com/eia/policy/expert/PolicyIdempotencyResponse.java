package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Stored idempotency row for a response-replay command.
 *
 * <p>{@code responseJson} is null while a concurrent command owns the
 * reservation; once that transaction commits the row always carries the
 * serialized response. {@code responseStatus} is the status of the original
 * response, not the replay status.</p>
 */
public record PolicyIdempotencyResponse(
        UUID id,
        String operation,
        String idempotencyKey,
        String responseJson,
        Integer responseStatus,
        Instant createdAt
) {
}
