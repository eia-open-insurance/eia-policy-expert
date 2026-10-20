package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Renew a policy into a new DRAFT term.
 *
 * <p>Only {@code expirationDate} is strictly required in the body:
 * {@code effectiveDate} defaults to the source expiration plus one day, and the
 * premium, limit, and currency fields default to the source policy values.
 * Supplying a field overrides the inherited term.</p>
 */
public record RenewPolicyRequest(
        LocalDate effectiveDate,
        @NotNull LocalDate expirationDate,
        @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 15, fraction = 4) BigDecimal premiumAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "premiumCurrency must be an ISO 4217 code")
        String premiumCurrency,
        @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 15, fraction = 4) BigDecimal limitAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "limitCurrency must be an ISO 4217 code")
        String limitCurrency
) {
}
