package com.eia.policy.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API projection of a {@link PolicyProgram}. The public contract exposes the
 * program id as {@code programId} and never leaks the tenant column.
 */
public record ProgramView(
        UUID programId,
        String programNumber,
        String name,
        String programType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        UUID leadPolicyId,
        Instant createdAt
) {
    static ProgramView of(PolicyProgram program) {
        return new ProgramView(program.id(), program.programNumber(), program.name(),
                program.programType(), program.effectiveFrom(), program.effectiveTo(),
                program.status(), program.leadPolicyId(), program.createdAt());
    }
}
