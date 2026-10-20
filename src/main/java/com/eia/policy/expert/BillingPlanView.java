package com.eia.policy.expert;

import java.util.List;

/**
 * Billing plan plus its ordered installment schedule, returned by GET and POST
 * {@code /policies/{policyId}/billing}.
 */
public record BillingPlanView(
        PolicyBillingPlan plan,
        List<PolicyInstallment> installments
) {
}
