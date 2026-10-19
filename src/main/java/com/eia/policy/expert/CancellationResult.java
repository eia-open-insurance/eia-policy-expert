package com.eia.policy.expert;

/** Result of a cancellation command. */
public record CancellationResult(PolicyCancellation cancellation, Policy policy, boolean replayed) {
}
