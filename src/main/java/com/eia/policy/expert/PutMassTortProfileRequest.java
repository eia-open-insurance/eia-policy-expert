package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Upsert body for a policy mass tort profile. {@code tortType}, {@code
 * triggerType}, {@code allocationMethod}, and {@code status} are always
 * required; the allocation notes and coverage block are optional for a
 * {@code DRAFT} profile and required to move it to {@code COMPLETE}.
 */
public record PutMassTortProfileRequest(
        @NotBlank String tortType,
        @NotBlank String triggerType,
        @NotBlank String allocationMethod,
        @Size(max = MassTortProfileRules.MAX_ALLOCATION_NOTES_LENGTH) String allocationNotes,
        LocalDate coverageBlockStart,
        LocalDate coverageBlockEnd,
        @NotBlank String status
) {
}
