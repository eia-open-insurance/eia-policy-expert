package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxHeaders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Event factory for the product catalog.
 *
 * <p>The requested event names are {@code product.product.created} and
 * {@code product.product.status-changed}, so the canonical vertical is
 * {@code product}; {@code eia-platform} now admits that vertical. The rows are
 * built directly as {@link OutboxEvent}s with the same payload and header JSON
 * the canonical envelope factory produces, and {@code OutboxEvent.toEnvelope()}
 * reconstructs the canonical envelope (and the subject) unchanged.</p>
 *
 * <p>A product is a catalog aggregate, so create is aggregate version one and
 * every accepted status change advances the row version, which makes the event
 * version distinct.</p>
 */
final class ProductEvents {
    static final String CREATED = "product.product.created";
    static final String STATUS_CHANGED = "product.product.status-changed";

    private static final String VERTICAL = "product";
    private static final String AGGREGATE_TYPE = "Product";
    private static final String SOURCE_BUILD = "eia-policy-expert@0.1.0-SNAPSHOT";

    private ProductEvents() {
    }

    static OutboxEvent created(Product product, Instant now) {
        Objects.requireNonNull(product, "product");
        Map<String, Object> data = productData(product);
        data.put("createdAt", product.createdAt());
        data.put("updatedAt", product.updatedAt());
        return event(product, CREATED, product.version() + 1L, data, now);
    }

    /**
     * A status change carries the previous and new status plus the new row
     * version and update instant, so a catalog consumer can apply the change
     * without a second read.
     */
    static OutboxEvent statusChanged(Product before, Product after, Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = productData(after);
        data.put("previousStatus", before.status());
        data.put("updatedAt", after.updatedAt());
        data.put("changedFields", List.of("status"));
        return event(after, STATUS_CHANGED, after.version() + 1L, data, now);
    }

    private static Map<String, Object> productData(Product product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id());
        data.put("productCode", product.productCode());
        data.put("productName", product.productName());
        data.put("productLine", product.productLine());
        data.put("jurisdiction", product.jurisdiction());
        data.put("status", product.status());
        data.put("version", product.version());
        return data;
    }

    private static OutboxEvent event(Product product, String eventType, long aggregateVersion,
                                     Map<String, Object> data, Instant now) {
        UUID eventId = EventIds.deterministic(product.tenantId(), AGGREGATE_TYPE, product.id(),
                aggregateVersion, eventType);
        OutboxHeaders headers = new OutboxHeaders(Book.DIRECT, VERTICAL, null, null, null,
                EventActor.system("eia-policy-expert"), null, List.of(),
                EventMetadata.v1(SOURCE_BUILD));
        return new OutboxEvent(
                eventId,
                0,
                product.tenantId(),
                AGGREGATE_TYPE,
                product.id(),
                aggregateVersion,
                eventType,
                1,
                EventJson.toJson(data),
                EventJson.toJson(headers),
                product.id().toString(),
                now,
                now,
                now,
                OutboxEvent.Status.PENDING,
                0,
                null,
                now,
                null);
    }
}
