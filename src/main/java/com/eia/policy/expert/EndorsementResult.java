package com.eia.policy.expert;

/** Result of an endorsement command. */
public record EndorsementResult(PolicyEndorsement endorsement, PolicyVersion version,
                                int policyVersion, boolean replayed) {
}
