package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Stored idempotency row for a response-replay command.
 *
 * <p>{@code requestHash} is the canonical SHA-256 of the command request. It is
 * written when the key is reserved so a later call can distinguish a replay
 * from a key reused with a different request. Rows created before request
 * hashing was added to a response command carry a null hash and are replayed
 * without comparison. {@code responseJson} is null while a concurrent command
 * owns the reservation; once that transaction commits the row always carries
 * the serialized response. {@code responseStatus} is the status of the original
 * response, not the replay status.</p>
 */
public record PolicyIdempotencyResponse(
        UUID id,
        String operation,
        String idempotencyKey,
        String requestHash,
        String responseJson,
        Integer responseStatus,
        Instant createdAt
) {
}
