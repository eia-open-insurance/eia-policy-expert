package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted policy aggregate for the first Policy vertical slice.
 */
public record Policy(
        UUID id,
        UUID tenantId,
        String policyNumber,
        String productCode,
        UUID insuredPartyId,
        String status,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumAmount,
        String premiumCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limitAmount,
        String limitCurrency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
