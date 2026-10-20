package com.eia.policy.expert;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Tenant-scoped installment facts needed to record a dunning action. The row is
 * resolved through the installment, its billing plan, and the policy, so the
 * tenant wall applies to all three tables.
 */
record BillingDunningTarget(
        UUID installmentId,
        UUID planId,
        UUID policyId,
        String storedStatus,
        LocalDate dueDate
) {
}
