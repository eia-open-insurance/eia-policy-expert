package com.eia.policy.expert;

/**
 * A coverage with its limit schedule and current erosion state.
 */
public record CoverageView(PolicyCoverage coverage, PolicyLimit limit, PolicyLimitState state) {
}
