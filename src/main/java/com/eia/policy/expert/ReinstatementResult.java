package com.eia.policy.expert;

/** Result of a reinstatement command. */
public record ReinstatementResult(PolicyReinstatement reinstatement, Policy policy, boolean replayed) {
}
