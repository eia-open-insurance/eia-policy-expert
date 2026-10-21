package com.eia.policy.expert;

import java.util.List;

/**
 * Billing plan plus its ordered installment schedule and the derived money
 * summary, returned by GET and POST {@code /policies/{policyId}/billing}.
 */
public record BillingPlanView(
        PolicyBillingPlan plan,
        List<PolicyInstallment> installments,
        BillingPlanSummary summary
) {
    public BillingPlanView {
        installments = installments == null ? List.of() : List.copyOf(installments);
        summary = summary == null ? BillingPlanSummary.of(plan, installments) : summary;
    }

    public BillingPlanView(PolicyBillingPlan plan, List<PolicyInstallment> installments) {
        this(plan, installments, BillingPlanSummary.of(plan, installments));
    }
}
