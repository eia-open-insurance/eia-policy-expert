package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Structured referral body for an underwriting submission. The reason,
 * authority, and assignee are required; notes are optional. The command moves
 * the submission to {@code REFERRED} and starts the decision at
 * {@code PENDING}.
 */
public record ReferSubmissionRequest(
        @NotBlank @Size(max = 64) String reason,
        @NotBlank @Size(max = 64) String authority,
        @NotBlank @Size(max = 128) String referredTo,
        @Size(max = 2000) String notes
) {
}
