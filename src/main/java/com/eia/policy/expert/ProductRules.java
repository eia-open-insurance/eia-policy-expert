package com.eia.policy.expert;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure product catalog invariants: the product-line and status vocabularies,
 * the code/name caps, and the two-letter jurisdiction.
 *
 * <p>A create body is normalized before validation so {@code cgl} and
 * {@code CGL} address the same tenant-unique code and line. The database
 * repeats the line, status, and jurisdiction checks as a backstop.</p>
 */
final class ProductRules {
    static final int MAX_CODE_LENGTH = 32;
    static final int MAX_NAME_LENGTH = 200;
    static final int JURISDICTION_LENGTH = 2;

    static final String CGL = "CGL";
    static final String PRODUCTS_LIABILITY = "PRODUCTS_LIABILITY";
    static final String UMBRELLA = "UMBRELLA";
    static final String EXCESS = "EXCESS";
    static final String OTHER = "OTHER";

    static final String ACTIVE = "ACTIVE";
    static final String RETIRED = "RETIRED";

    private static final List<String> PRODUCT_LINES =
            List.of(CGL, PRODUCTS_LIABILITY, UMBRELLA, EXCESS, OTHER);
    private static final Set<String> PRODUCT_LINE_SET = Set.copyOf(PRODUCT_LINES);
    private static final List<String> STATUSES = List.of(ACTIVE, RETIRED);
    private static final Set<String> STATUS_SET = Set.copyOf(STATUSES);

    private ProductRules() {
    }

    /**
     * Normalizes and validates one create body. Missing or blank required
     * fields are 400s; an overlong code or name, an unknown product line, and a
     * non alpha-2 jurisdiction are field-specific 400s.
     */
    static ProductSpec validateCreate(CreateProductRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT", "productCode",
                    "A product request is required.");
        }
        return new ProductSpec(
                normalizeCode(request.productCode()),
                normalizeName(request.productName()),
                normalizeProductLine(request.productLine()),
                normalizeJurisdiction(request.jurisdiction()));
    }

    static String normalizeCode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_CODE", "productCode",
                    "productCode is required.");
        }
        if (value.length() > MAX_CODE_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_CODE", "productCode",
                    "productCode must be at most " + MAX_CODE_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_NAME", "productName",
                    "productName is required.");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_NAME", "productName",
                    "productName must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeProductLine(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCT_LINE_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_LINE", "productLine",
                    "productLine must be one of " + String.join(", ", PRODUCT_LINES) + ".");
        }
        return value;
    }

    static String normalizeJurisdiction(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.length() != JURISDICTION_LENGTH || !value.chars().allMatch(
                ch -> ch >= 'A' && ch <= 'Z')) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_JURISDICTION", "jurisdiction",
                    "jurisdiction must be a two-letter ISO 3166-1 alpha-2 code.");
        }
        return value;
    }

    static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_PRODUCT_STATUS", "status",
                    "status must be ACTIVE or RETIRED.");
        }
        return value;
    }

    record ProductSpec(
            String productCode,
            String productName,
            String productLine,
            String jurisdiction
    ) {
    }
}
