package com.eia.policy.expert;

import java.math.BigDecimal;

/**
 * Derived policy aggregates for one commercial program.
 *
 * <p>{@code policyCount} is the number of policies assigned to the program in
 * the tenant, and {@code usedAmount} is the sum of their
 * {@code policy.limit_amount}. A program without assigned policies reports a
 * zero count and a zero scale-4 amount.</p>
 */
public record ProgramUsage(long policyCount, BigDecimal usedAmount) {
    static final ProgramUsage NONE = new ProgramUsage(0, Money.of(BigDecimal.ZERO));

    public ProgramUsage {
        if (policyCount < 0) {
            throw new IllegalArgumentException("policyCount must not be negative");
        }
        usedAmount = usedAmount == null ? Money.of(BigDecimal.ZERO) : Money.of(usedAmount);
    }
}
