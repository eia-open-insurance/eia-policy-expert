package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Tenant and book scope for the tenant-wide read routes. The internal expert
 * convention passes {@code ?tenantId=}, while the public API convention passes
 * {@code X-EIA-Tenant}; both are accepted. Policy is fixed to the DIRECT book,
 * so a missing {@code X-EIA-Book} header defaults to DIRECT and any other book
 * is treated as a non-existent resource.
 */
final class RequestScope {
    static final String TENANT_HEADER = "X-EIA-Tenant";
    static final String BOOK_HEADER = "X-EIA-Book";
    static final String DIRECT = "DIRECT";

    private RequestScope() {
    }

    static UUID tenant(UUID tenantIdParam, String tenantHeader) {
        if (tenantIdParam != null) {
            return tenantIdParam;
        }
        if (tenantHeader == null || tenantHeader.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_TENANT_HEADER",
                    "A tenantId query parameter or X-EIA-Tenant header is required.");
        }
        try {
            return UUID.fromString(tenantHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TENANT_HEADER",
                    "X-EIA-Tenant must be a UUID.");
        }
    }

    static void requireDirectBook(String bookHeader) {
        if (bookHeader != null && !bookHeader.isBlank()
                && !DIRECT.equalsIgnoreCase(bookHeader.trim())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
        }
    }
}
