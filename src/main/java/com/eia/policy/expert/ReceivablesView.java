package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tenant-wide premium receivables summary. Every item is an open, collectible
 * installment (PAID, WAIVED, and fully CREDITED rows are excluded).
 *
 * <p>Totals use each item's {@code outstandingAmount}, not its gross
 * {@code amount}: {@code totalAmount} is the sum of the uncredited remainders
 * and {@code overdueAmount} is the subset whose effective status is OVERDUE.
 * {@code count} is the number of matching items.</p>
 */
public record ReceivablesView(
        List<InstallmentReceivable> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overdueAmount,
        int count
) {
}
