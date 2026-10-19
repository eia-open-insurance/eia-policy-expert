package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Limit schedule for one coverage. {@code aggregateAmount == null} means the
 * aggregate is explicitly NONE (SPEC POL-03).
 */
public record PolicyLimit(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID coverageId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perOccurrenceAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal aggregateAmount,
        String aggregateBasis,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sublimitAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deductibleAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal coinsurancePct,
        String currency,
        String erosionBasis,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int sequence,
        Instant createdAt
) {
    /** The amount the aggregate erosion ledger starts from (SPEC POL-11). */
    public BigDecimal erosionBase() {
        return aggregateAmount != null ? aggregateAmount : perOccurrenceAmount;
    }
}
