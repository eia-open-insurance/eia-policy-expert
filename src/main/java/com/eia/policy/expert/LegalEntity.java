package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Legal entity master data row (SPEC 10.11.2, Policy slice issuing entity).
 *
 * <p>A legal entity is the regulated carrier, MGA, captive, pool, or fronter
 * that issues or fronts a policy. The code is unique per tenant and immutable
 * after first use; status is ACTIVE on create, with INACTIVE reserved for a
 * later retirement command.</p>
 */
public record LegalEntity(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        String entityType,
        String jurisdiction,
        String regulator,
        String licenseNumber,
        String status,
        Instant createdAt
) {
}
