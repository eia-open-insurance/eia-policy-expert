package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API projection of a {@link PolicyProgram}. The public contract exposes the
 * program id as {@code programId} and never leaks the tenant column.
 *
 * <p>{@code capacityAmount}/{@code capacityCurrency} are the optional program
 * capacity. {@code usedAmount} is the sum of the limits of the policies
 * assigned to the program in the tenant, {@code policyCount} is how many
 * policies those are, and {@code utilizationPct} is
 * {@code usedAmount / capacityAmount * 100} rounded to two decimals. A program
 * without capacity reports a null {@code utilizationPct}; a program without
 * assigned policies reports a zero {@code usedAmount}.</p>
 */
public record ProgramView(
        UUID programId,
        String programNumber,
        String name,
        String programType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal capacityAmount,
        String capacityCurrency,
        UUID leadPolicyId,
        Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal utilizationPct,
        long policyCount
) {
    public ProgramView {
        capacityAmount = Money.of(capacityAmount);
        usedAmount = usedAmount == null ? Money.of(BigDecimal.ZERO) : Money.of(usedAmount);
        utilizationPct = utilizationPct == null
                ? null : utilizationPct.setScale(2, RoundingMode.HALF_UP);
        policyCount = Math.max(0, policyCount);
    }

    /**
     * Compatibility constructor for projections that predate capacity usage.
     */
    public ProgramView(UUID programId, String programNumber, String name, String programType,
                       LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                       UUID leadPolicyId, Instant createdAt) {
        this(programId, programNumber, name, programType, effectiveFrom, effectiveTo, status,
                null, null, leadPolicyId, createdAt, null, null, 0L);
    }

    static ProgramView of(PolicyProgram program, ProgramUsage usage) {
        ProgramUsage effective = usage == null ? ProgramUsage.NONE : usage;
        BigDecimal capacity = Money.of(program.capacityAmount());
        BigDecimal used = effective.usedAmount();
        return new ProgramView(program.id(), program.programNumber(), program.name(),
                program.programType(), program.effectiveFrom(), program.effectiveTo(),
                program.status(), capacity, program.capacityCurrency(),
                program.leadPolicyId(), program.createdAt(), used,
                ProgramRules.utilizationPct(used, capacity), effective.policyCount());
    }

    /**
     * Projection for a just-created program, which cannot have assigned
     * policies yet.
     */
    static ProgramView of(PolicyProgram program) {
        return of(program, ProgramUsage.NONE);
    }
}
