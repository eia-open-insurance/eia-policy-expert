package com.eia.policy.expert;

/** Result of a coverage command. */
public record CoverageResult(CoverageView coverage, int policyVersion, boolean replayed) {
}
