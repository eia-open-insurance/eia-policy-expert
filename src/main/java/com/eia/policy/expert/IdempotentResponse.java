package com.eia.policy.expert;

/**
 * Outcome of an idempotent command: the response body, the HTTP status of the
 * current call, and whether the body came from a stored replay.
 *
 * <p>A first execution reports the command's creation status and
 * {@code replayed=false}. A replay always reports {@code 200} with
 * {@code replayed=true}; the original status remains available through the
 * stored idempotency row.</p>
 */
public record IdempotentResponse<T>(T body, int status, boolean replayed) {
}
