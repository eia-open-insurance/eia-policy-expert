package com.eia.policy.expert;

import java.util.List;

/**
 * Child rows that make up a policy version snapshot. The canonical hash of this
 * snapshot plus the policy and version metadata is the version's
 * {@code immutableHash} (SPEC POL-06).
 */
public record PolicySnapshot(
        List<PolicyCoverage> coverages,
        List<PolicyLimit> limits,
        List<PolicyInsured> insureds,
        List<PolicyLocation> locations,
        List<PolicyEndorsement> endorsements
) {
    public static PolicySnapshot empty() {
        return new PolicySnapshot(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
