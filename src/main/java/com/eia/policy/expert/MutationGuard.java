package com.eia.policy.expert;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Loads the policy for a mutation and enforces the optional {@code If-Match}
 * precondition. Every second-slice mutation carries the same strong entity tag
 * (the aggregate {@code version}) as the first-slice status transition.
 */
@Component
public class MutationGuard {
    private final PolicyRepository policyRepository;

    public MutationGuard(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    public Policy requirePolicy(UUID tenantId, UUID policyId, String ifMatch) {
        Policy policy = policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
        Integer expected = EntityTags.parse(ifMatch);
        if (expected != null && expected != EntityTags.ANY && expected != policy.version()) {
            String message = "Expected version " + expected + " but policy is at version "
                    + policy.version() + "; reload and retry";
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONCURRENT_MODIFICATION", message,
                    java.util.List.of(new ApiException.FieldIssue(
                            "If-Match", "STALE_VERSION", message)),
                    Map.of("currentVersion", policy.version(), "expectedVersion", expected));
        }
        return policy;
    }
}
