package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreatePolicyRequest(
        @NotNull UUID tenantId,
        @Size(max = 32) String policyNumber,
        @NotNull @Size(max = 32) String productCode,
        @NotNull UUID insuredPartyId,
        String status,
        @NotNull LocalDate effectiveDate,
        @NotNull LocalDate expirationDate,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 15, fraction = 4) BigDecimal premiumAmount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "premiumCurrency must be an ISO 4217 code") String premiumCurrency,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 15, fraction = 4) BigDecimal limitAmount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "limitCurrency must be an ISO 4217 code") String limitCurrency
) {}
