package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Money summary of a billing plan, returned with the plan read.
 *
 * <p>{@code totalAmount} is the plan premium, {@code paidAmount} and
 * {@code waivedAmount} are the settled portions, and {@code creditedAmount} is
 * the sum of every credit note applied to the schedule. {@code outstandingAmount}
 * is the sum of each installment's outstanding amount, so WAIVED, PAID, and
 * fully CREDITED rows never contribute; it is the
 * {@code total - paid - waived - credited} remainder clamped at zero when an
 * installment was settled after a partial credit.</p>
 */
public record BillingPlanSummary(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal waivedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overdueAmount,
        String currency,
        int installmentCount
) {
    public BillingPlanSummary {
        totalAmount = money(totalAmount);
        paidAmount = money(paidAmount);
        waivedAmount = money(waivedAmount);
        creditedAmount = money(creditedAmount);
        outstandingAmount = money(outstandingAmount);
        overdueAmount = money(overdueAmount);
        currency = currency == null || currency.isBlank() ? null : currency;
    }

    static BillingPlanSummary of(PolicyBillingPlan plan, List<PolicyInstallment> installments) {
        List<PolicyInstallment> rows = installments == null ? List.of() : installments;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal waived = BigDecimal.ZERO;
        BigDecimal credited = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        for (PolicyInstallment installment : rows) {
            BigDecimal amount = Money.of(installment.amount());
            BigDecimal installmentCredited = Money.of(installment.creditedAmount());
            if (amount != null && BillingRules.PAID.equals(installment.status())) {
                paid = paid.add(amount);
            } else if (amount != null && BillingRules.WAIVED.equals(installment.status())) {
                waived = waived.add(amount);
            }
            if (installmentCredited != null) {
                credited = credited.add(installmentCredited);
            }
            BigDecimal open = installment.outstandingAmount() == null
                    ? BigDecimal.ZERO : installment.outstandingAmount();
            outstanding = outstanding.add(open);
            if (BillingRules.OVERDUE.equals(installment.status())) {
                overdue = overdue.add(open);
            }
        }
        BigDecimal total = Money.of(plan == null ? BigDecimal.ZERO : plan.totalPremium());
        return new BillingPlanSummary(Money.of(total), Money.of(paid), Money.of(waived),
                Money.of(credited), Money.of(outstanding), Money.of(overdue),
                plan == null ? null : plan.currency(), rows.size());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? Money.of(BigDecimal.ZERO) : Money.of(value);
    }
}
