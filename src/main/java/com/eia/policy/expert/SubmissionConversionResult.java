package com.eia.policy.expert;

/**
 * Outcome of one submission-to-policy conversion command.
 *
 * <p>{@code replayed} is true when the submission was already BOUND and its
 * linked policy already existed, in which case the command changes nothing and
 * returns that policy. {@code submission} is the projection after conversion
 * (BOUND with {@code policyId} populated).</p>
 */
public record SubmissionConversionResult(
        Policy policy,
        UnderwritingSubmissionView submission,
        boolean replayed
) {
}
