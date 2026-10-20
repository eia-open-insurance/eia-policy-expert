package com.eia.policy.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mass tort profile of a policy (SPEC 11.2.4 {@code policy_mass_tort_profile}).
 *
 * <p>There is exactly one profile per policy. Long-tail claims use
 * {@code tortType}, the coverage trigger theory, and the allocation method to
 * distribute a claim across policy years. A {@code DRAFT} profile may omit the
 * allocation notes and the coverage block; a {@code COMPLETE} profile may not,
 * because Direct Claims consumes the event to drive allocation.</p>
 */
public record PolicyMassTortProfile(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String tortType,
        String triggerType,
        String allocationMethod,
        String allocationNotes,
        LocalDate coverageBlockStart,
        LocalDate coverageBlockEnd,
        String status,
        Instant updatedAt
) {
}
