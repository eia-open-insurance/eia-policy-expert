package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * API projection of a {@link Product}. The public contract exposes the id as
 * {@code productId} and never leaks the tenant column. {@code version} is the
 * aggregate sequence (zero after create, one higher after each accepted status
 * change); the mutation responses carry it as the {@code ETag}.
 */
public record ProductView(
        UUID productId,
        String productCode,
        String productName,
        String productLine,
        String jurisdiction,
        String status,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    static ProductView of(Product product) {
        return new ProductView(product.id(), product.productCode(), product.productName(),
                product.productLine(), product.jurisdiction(), product.status(),
                product.version(), product.createdAt(), product.updatedAt());
    }
}
