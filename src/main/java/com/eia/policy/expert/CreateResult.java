package com.eia.policy.expert;

/**
 * Result of a create command. {@code replayed} is true when the stored
 * idempotency record returned the original policy instead of creating a new one.
 */
public record CreateResult(Policy policy, boolean replayed) {
}
