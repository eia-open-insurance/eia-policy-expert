package com.eia.policy.expert;

/** Result of a location command. */
public record LocationResult(PolicyLocation location, int policyVersion, boolean replayed) {
}
