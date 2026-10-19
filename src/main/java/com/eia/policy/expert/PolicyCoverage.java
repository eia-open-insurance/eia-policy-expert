package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Coverage grant on a policy (SPEC 11.2.4 `policy_coverage`). */
public record PolicyCoverage(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String coverageCode,
        String coverageName,
        String coveragePart,
        String occurrenceBasis,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal coinsurancePct,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        int sequence,
        Instant createdAt
) {
}
