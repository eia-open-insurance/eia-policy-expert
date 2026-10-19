package com.eia.policy.expert;

import java.util.UUID;

/** Stored idempotency record: the request hash and the policy it created. */
public record IdempotencyRecord(UUID policyId, String requestHash) {
}
