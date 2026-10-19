package com.eia.policy.expert;

import java.util.UUID;

/** Stored command idempotency row: the resource that a replayed call returns. */
public record CommandIdempotencyRecord(UUID resourceId, String requestHash) {
}
