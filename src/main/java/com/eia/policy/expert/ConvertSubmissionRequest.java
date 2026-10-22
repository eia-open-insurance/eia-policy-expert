package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Convert a quoted or bound underwriting submission into a DRAFT policy.
 *
 * <p>The premium is required because a policy cannot be written without it; the
 * expiration is optional and defaults to one year after the submission's
 * requested effective date. Every other policy term comes from the submission.</p>
 */
public record ConvertSubmissionRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 15, fraction = 4) BigDecimal premiumAmount,
        @NotNull @Pattern(regexp = "[A-Z]{3}",
                message = "premiumCurrency must be an ISO 4217 code") String premiumCurrency,
        LocalDate expirationDate
) {
}
