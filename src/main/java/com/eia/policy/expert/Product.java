package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Product catalog row (SPEC 11.2.14 {@code product}, narrowed to the requested
 * slice).
 *
 * <p>A product is the sellable line of business a policy or submission refers
 * to. The code is unique per tenant and stored upper-case; a product is created
 * ACTIVE and can be RETIRED (and reactivated). The row version is the aggregate
 * sequence carried by the status-changed outbox event; it starts at zero on
 * create, matching the policy and submission convention that the first event is
 * aggregate version one.</p>
 */
public record Product(
        UUID id,
        UUID tenantId,
        String productCode,
        String productName,
        String productLine,
        String jurisdiction,
        String status,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
