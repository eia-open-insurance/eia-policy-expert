package com.eia.policy.expert;

/**
 * Derived current version of a policy. {@code hashVerified} is recomputed from
 * the live snapshot, proving SPEC POL-06.
 */
public record CurrentVersion(PolicyVersion version, boolean current, boolean hashVerified) {
}
