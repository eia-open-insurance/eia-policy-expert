package com.eia.policy.expert;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create body for the commercial program catalog. The program number, status,
 * and creation time are server-owned.
 *
 * <p>Capacity is optional. When supplied, the amount must be positive and must
 * carry a three-letter ISO 4217 currency, so the two fields are both present or
 * both absent.</p>
 */
public record CreateProgramRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank String programType,
        @NotNull LocalDate effectiveFrom,
        @NotNull LocalDate effectiveTo,
        UUID leadPolicyId,
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal capacityAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "capacityCurrency must be an ISO 4217 code")
        String capacityCurrency
) {
    /**
     * Compatibility constructor for bodies that do not set capacity.
     */
    public CreateProgramRequest(String name, String programType, LocalDate effectiveFrom,
                                LocalDate effectiveTo, UUID leadPolicyId) {
        this(name, programType, effectiveFrom, effectiveTo, leadPolicyId, null, null);
    }
}
