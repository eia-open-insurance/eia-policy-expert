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
 * {@code lastDunningLevel} and {@code lastDunningAt} come from the latest SENT
 * dunning action and are null when the installment has never been dunned.
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
        String currency,
        String status,
        long daysPastDue,
        Instant paidAt,
        UUID paymentId,
        String lastDunningLevel,
        Instant lastDunningAt
) {
}
