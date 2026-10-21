package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * API projection of a {@link LegalEntity}. The public contract exposes the id
 * as {@code legalEntityId} and never leaks the tenant column.
 */
public record LegalEntityView(
        UUID legalEntityId,
        String code,
        String name,
        String entityType,
        String jurisdiction,
        String regulator,
        String licenseNumber,
        String status,
        Instant createdAt
) {
    static LegalEntityView of(LegalEntity legalEntity) {
        return new LegalEntityView(legalEntity.id(), legalEntity.code(), legalEntity.name(),
                legalEntity.entityType(), legalEntity.jurisdiction(), legalEntity.regulator(),
                legalEntity.licenseNumber(), legalEntity.status(), legalEntity.createdAt());
    }
}
