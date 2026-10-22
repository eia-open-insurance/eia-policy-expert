package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Status-transition body for an underwriting submission. The reason is
 * optional and is carried on the {@code policy.submission.status-changed}
 * event; the aggregate stores the status and the new version.
 */
public record UpdateUnderwritingSubmissionStatusRequest(
        @NotBlank String status,
        @Size(max = 500) String reason
) {
}
