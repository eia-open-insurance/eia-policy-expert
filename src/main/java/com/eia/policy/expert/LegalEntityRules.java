package com.eia.policy.expert;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure legal entity catalog invariants: the entity-type and status
 * vocabularies, the business-code and name caps, the ISO 3166-1 alpha-2
 * jurisdiction, and the optional regulator/license caps.
 *
 * <p>The database repeats the vocabulary, jurisdiction, and status checks as a
 * backstop. A legal entity is created ACTIVE; there is no retirement command in
 * this slice, so the INACTIVE state is reserved for a later lifecycle command.</p>
 */
final class LegalEntityRules {
    static final int MAX_CODE_LENGTH = 32;
    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_REGULATOR_LENGTH = 120;
    static final int MAX_LICENSE_NUMBER_LENGTH = 80;
    static final int JURISDICTION_LENGTH = 2;

    static final String INSURER = "INSURER";
    static final String MGA = "MGA";
    static final String CAPTIVE = "CAPTIVE";
    static final String POOL = "POOL";
    static final String FRONTER = "FRONTER";

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private static final List<String> ENTITY_TYPES =
            List.of(INSURER, MGA, CAPTIVE, POOL, FRONTER);
    private static final Set<String> ENTITY_TYPE_SET = Set.copyOf(ENTITY_TYPES);
    private static final Set<String> STATUSES = Set.of(ACTIVE, INACTIVE);

    private LegalEntityRules() {
    }

    /**
     * Normalizes and validates one create body. Missing or blank required
     * fields are 400s; an unknown entity type or a non alpha-2 jurisdiction is
     * a field-specific 400.
     */
    static LegalEntitySpec validateCreate(CreateLegalEntityRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY", "code",
                    "A legal entity request is required.");
        }
        return new LegalEntitySpec(
                normalizeCode(request.code()),
                normalizeName(request.name()),
                normalizeEntityType(request.entityType()),
                normalizeJurisdiction(request.jurisdiction()),
                normalizeOptional(request.regulator(), MAX_REGULATOR_LENGTH,
                        "INVALID_REGULATOR", "regulator"),
                normalizeOptional(request.licenseNumber(), MAX_LICENSE_NUMBER_LENGTH,
                        "INVALID_LICENSE_NUMBER", "licenseNumber"));
    }

    static String normalizeCode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_CODE", "code",
                    "code is required.");
        }
        if (value.length() > MAX_CODE_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_CODE", "code",
                    "code must be at most " + MAX_CODE_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_NAME", "name",
                    "name is required.");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_NAME", "name",
                    "name must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeEntityType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ENTITY_TYPE_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_TYPE", "entityType",
                    "entityType must be one of " + String.join(", ", ENTITY_TYPES) + ".");
        }
        return value;
    }

    static String normalizeJurisdiction(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.length() != JURISDICTION_LENGTH || !value.chars().allMatch(
                ch -> ch >= 'A' && ch <= 'Z')) {
            throw PolicyErrors.badRequest("INVALID_JURISDICTION", "jurisdiction",
                    "jurisdiction must be a two-letter ISO 3166-1 alpha-2 code.");
        }
        return value;
    }

    static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_LEGAL_ENTITY_STATUS", "status",
                    "status must be ACTIVE or INACTIVE.");
        }
        return value;
    }

    private static String normalizeOptional(String raw, int maxLength, String code, String field) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw PolicyErrors.badRequest(code, field,
                    field + " must be at most " + maxLength + " characters.");
        }
        return value;
    }

    record LegalEntitySpec(
            String code,
            String name,
            String entityType,
            String jurisdiction,
            String regulator,
            String licenseNumber
    ) {
    }
}
