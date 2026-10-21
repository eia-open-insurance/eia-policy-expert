package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One unsettled premium installment on the tenant-wide receivables worklist.
 * {@code status} is the effective status: a stored DUE row whose due date is in
 * the past is reported as OVERDUE without changing the stored row.
 *
 * <p>{@code amount} stays the gross scheduled amount, {@code creditedAmount} is
 * the sum of the credit notes applied to it, and {@code outstandingAmount} is
 * {@code amount - creditedAmount} for the open, collectible remainder.
 * {@code lastDunningLevel} and {@code lastDunningAt} come from the latest SENT
 * dunning action and are null when the installment has never been dunned.</p>
 */
public record InstallmentReceivable(
        UUID installmentId,
        UUID planId,
        String planNumber,
        UUID policyId,
        String policyNumber,
        UUID insuredPartyId,
        int installmentNumber,
        LocalDate dueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingAmount,
        String currency,
        String status,
        long daysPastDue,
        Instant paidAt,
        UUID paymentId,
        String lastDunningLevel,
        Instant lastDunningAt
) {
    public InstallmentReceivable {
        creditedAmount = creditedAmount == null
                ? Money.of(BigDecimal.ZERO) : Money.of(creditedAmount);
        outstandingAmount = outstandingAmount == null
                ? BillingRules.outstandingAmount(amount, creditedAmount, "DUE")
                : Money.of(outstandingAmount);
    }

    /** Legacy shape for an open receivable that predates credit application. */
    public InstallmentReceivable(UUID installmentId, UUID planId, String planNumber,
                                 UUID policyId, String policyNumber, UUID insuredPartyId,
                                 int installmentNumber, LocalDate dueDate, BigDecimal amount,
                                 String currency, String status, long daysPastDue, Instant paidAt,
                                 UUID paymentId, String lastDunningLevel, Instant lastDunningAt) {
        this(installmentId, planId, planNumber, policyId, policyNumber, insuredPartyId,
                installmentNumber, dueDate, amount, BigDecimal.ZERO, null, currency, status,
                daysPastDue, paidAt, paymentId, lastDunningLevel, lastDunningAt);
    }

    /**
     * Compatibility shape with the credit amounts appended after the original
     * receivable members.
     */
    public InstallmentReceivable(UUID installmentId, UUID planId, String planNumber,
                                 UUID policyId, String policyNumber, UUID insuredPartyId,
                                 int installmentNumber, LocalDate dueDate, BigDecimal amount,
                                 String currency, String status, long daysPastDue, Instant paidAt,
                                 UUID paymentId, String lastDunningLevel, Instant lastDunningAt,
                                 BigDecimal creditedAmount, BigDecimal outstandingAmount) {
        this(installmentId, planId, planNumber, policyId, policyNumber, insuredPartyId,
                installmentNumber, dueDate, amount, creditedAmount, outstandingAmount, currency,
                status, daysPastDue, paidAt, paymentId, lastDunningLevel, lastDunningAt);
    }
}
