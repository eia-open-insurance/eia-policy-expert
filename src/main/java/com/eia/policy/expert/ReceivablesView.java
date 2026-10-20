package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tenant-wide premium receivables summary. Totals are over the installments
 * returned in {@code items}; {@code overdueAmount} is the subset whose
 * effective status is OVERDUE.
 */
public record ReceivablesView(
        List<InstallmentReceivable> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overdueAmount,
        int count
) {
}
