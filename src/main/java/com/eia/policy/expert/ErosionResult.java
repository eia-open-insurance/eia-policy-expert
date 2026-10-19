package com.eia.policy.expert;

/** Result of an erosion command. */
public record ErosionResult(PolicyLimitState state, int policyVersion, boolean replayed) {
}
