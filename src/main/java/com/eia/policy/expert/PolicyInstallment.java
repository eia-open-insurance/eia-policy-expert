package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One scheduled premium installment of a billing plan. {@code paidAt} and
 * {@code paymentId} are populated only for PAID rows; WAIVED and CREDITED rows
 * carry no payment evidence.
 *
 * <p>{@code creditedAmount} is the sum of the credit notes applied to the
 * installment and is persisted. {@code outstandingAmount} is derived at read
 * time as the gross amount less the applied credits for a DUE/OVERDUE row; a
 * PAID, WAIVED, or fully CREDITED row owes nothing.</p>
 */
public record PolicyInstallment(
        UUID id,
        UUID tenantId,
        UUID planId,
        int installmentNumber,
        LocalDate dueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingAmount,
        String status,
        Instant paidAt,
        UUID paymentId,
        Instant createdAt
) {
    public PolicyInstallment {
        amount = Money.of(amount);
        creditedAmount = creditedAmount == null
                ? Money.of(BigDecimal.ZERO) : Money.of(creditedAmount);
        outstandingAmount = outstandingAmount == null
                ? BillingRules.outstandingAmount(amount, creditedAmount, status)
                : Money.of(outstandingAmount);
    }

    /**
     * Creation/legacy shape for an installment with no applied credits. The
     * outstanding amount is the full amount.
     */
    public PolicyInstallment(UUID id, UUID tenantId, UUID planId, int installmentNumber,
                             LocalDate dueDate, BigDecimal amount, String status,
                             Instant paidAt, UUID paymentId, Instant createdAt) {
        this(id, tenantId, planId, installmentNumber, dueDate, amount,
                Money.of(BigDecimal.ZERO), Money.of(amount), status, paidAt, paymentId,
                createdAt);
    }

    /**
     * Convenience shape that derives the outstanding amount from the credited
     * amount instead of storing it.
     */
    public PolicyInstallment(UUID id, UUID tenantId, UUID planId, int installmentNumber,
                             LocalDate dueDate, BigDecimal amount, BigDecimal creditedAmount,
                             String status, Instant paidAt, UUID paymentId, Instant createdAt) {
        this(id, tenantId, planId, installmentNumber, dueDate, amount, creditedAmount,
                BillingRules.outstandingAmount(amount, creditedAmount, status), status, paidAt,
                paymentId, createdAt);
    }

    /**
     * Compatibility shape with the credit amounts appended after the settled
     * facts, matching projections that were widened without reordering the
     * original members.
     */
    public PolicyInstallment(UUID id, UUID tenantId, UUID planId, int installmentNumber,
                             LocalDate dueDate, BigDecimal amount, String status,
                             Instant paidAt, UUID paymentId, BigDecimal creditedAmount,
                             BigDecimal outstandingAmount, Instant createdAt) {
        this(id, tenantId, planId, installmentNumber, dueDate, amount, creditedAmount,
                outstandingAmount, status, paidAt, paymentId, createdAt);
    }
}
