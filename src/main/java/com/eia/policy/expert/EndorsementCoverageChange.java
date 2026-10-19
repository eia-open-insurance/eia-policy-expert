package com.eia.policy.expert;

/**
 * One coverage change carried by an endorsement. {@code changeType} is
 * {@code ADD}, {@code REMOVE}, {@code BROADEN}, {@code RESTRICT} or
 * {@code CLARIFY}.
 */
public record EndorsementCoverageChange(String coverageCode, String changeType, String description) {
}
