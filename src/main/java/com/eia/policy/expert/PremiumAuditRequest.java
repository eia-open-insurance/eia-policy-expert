package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Apply a premium audit to an issued policy.
 *
 * <p>{@code auditedPremium} is the final written premium after the audit and
 * must be non-negative. {@code effectiveDate} is the business date of the audit
 * and is resolved against the version timeline. {@code reason} is mandatory and
 * is carried on both the endorsement and the event. {@code approvedBy} and
 * {@code approvalRef} are only required when the audit is backdated before
 * policy inception or would regress the version timeline.</p>
 */
public record PremiumAuditRequest(
        @NotNull @DecimalMin(value = "0.00", message = "auditedPremium must be non-negative")
        @Digits(integer = 15, fraction = 4) BigDecimal auditedPremium,
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 500) String reason,
        UUID approvedBy,
        UUID approvalRef
) {
}
