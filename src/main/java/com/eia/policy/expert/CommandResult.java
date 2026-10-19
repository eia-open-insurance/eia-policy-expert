package com.eia.policy.expert;

/**
 * Outcome of an idempotent command: the resource plus a replay marker.
 */
public record CommandResult<T>(T value, boolean replayed) {
}
