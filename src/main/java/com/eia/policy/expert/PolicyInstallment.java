package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One scheduled premium installment of a billing plan. {@code paidAt} and
 * {@code paymentId} are populated only for PAID rows; WAIVED rows carry no
 * payment evidence.
 */
public record PolicyInstallment(
        UUID id,
        UUID tenantId,
        UUID planId,
        int installmentNumber,
        LocalDate dueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String status,
        Instant paidAt,
        UUID paymentId,
        Instant createdAt
) {
}
