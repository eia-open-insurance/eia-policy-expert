package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One tenant-scoped receivable row joined from the installment, billing plan,
 * and policy tables. {@code storedStatus} is the persisted installment status;
 * the response derives the effective overdue status from the due date instead
 * of mutating the row.
 */
record ReceivableRow(
        UUID installmentId,
        UUID planId,
        String planNumber,
        UUID policyId,
        String policyNumber,
        UUID insuredPartyId,
        int installmentNumber,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        String storedStatus,
        Instant paidAt,
        UUID paymentId
) {
}
