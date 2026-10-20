package com.eia.policy.expert;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure program-catalog invariants: the program-type and status vocabularies,
 * the name cap, and the inclusive effective date range.
 *
 * <p>The database repeats the vocabulary and date checks as a backstop. A
 * program is created ACTIVE; there is no close command in this slice, so the
 * CLOSED state is reserved for a later lifecycle command.</p>
 */
final class ProgramRules {
    static final int MAX_NAME_LENGTH = 200;

    static final String CAPTIVE = "CAPTIVE";
    static final String POOL = "POOL";
    static final String FRONTING = "FRONTING";
    static final String COINSURANCE = "COINSURANCE";
    static final String MASS_TORT = "MASS_TORT";

    static final String ACTIVE = "ACTIVE";
    static final String CLOSED = "CLOSED";

    private static final List<String> PROGRAM_TYPES =
            List.of(CAPTIVE, POOL, FRONTING, COINSURANCE, MASS_TORT);
    private static final Set<String> PROGRAM_TYPE_SET = Set.copyOf(PROGRAM_TYPES);
    private static final Set<String> STATUSES = Set.of(ACTIVE, CLOSED);

    private ProgramRules() {
    }

    /**
     * Normalizes and validates one create body. Missing or blank required
     * fields are 400s; an unknown program type is a field-specific 400.
     */
    static ProgramSpec validateCreate(CreateProgramRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM", "name",
                    "A program request is required.");
        }
        String name = normalizeName(request.name());
        String programType = normalizeProgramType(request.programType());
        LocalDate effectiveFrom = request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveFrom == null) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_DATES", "effectiveFrom",
                    "effectiveFrom is required.");
        }
        if (effectiveTo == null) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_DATES", "effectiveTo",
                    "effectiveTo is required.");
        }
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_DATES", "effectiveTo",
                    "effectiveTo must not be before effectiveFrom.");
        }
        return new ProgramSpec(name, programType, effectiveFrom, effectiveTo,
                request.leadPolicyId());
    }

    static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_NAME", "name",
                    "name is required.");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_NAME", "name",
                    "name must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeProgramType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!PROGRAM_TYPE_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_TYPE", "programType",
                    "programType must be one of " + String.join(", ", PROGRAM_TYPES) + ".");
        }
        return value;
    }

    static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_PROGRAM_STATUS", "status",
                    "status must be ACTIVE or CLOSED.");
        }
        return value;
    }

    record ProgramSpec(
            String name,
            String programType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID leadPolicyId
    ) {
    }
}
