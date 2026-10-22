package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Result of a positive premium adjustment: the updated plan, the appended
 * installment, and the adjustment facts.
 *
 * <p>{@code adjustmentAmount} is the positive premium remainder that was
 * appended, and {@code newTotalPremium} is the updated gross plan total. The
 * plan's net premium (that total less the credits already applied to the
 * schedule) equals the policy written premium after the adjustment. Both are
 * scale-4 money strings, matching the rest of the Policy wire model.</p>
 */
public record BillingAdjustmentView(
        PolicyBillingPlan plan,
        PolicyInstallment installment,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal adjustmentAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newTotalPremium
) {
    public BillingAdjustmentView {
        adjustmentAmount = Money.of(adjustmentAmount);
        newTotalPremium = Money.of(newTotalPremium == null && plan != null
                ? plan.totalPremium() : newTotalPremium);
    }
}
