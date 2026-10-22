package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product catalog commands and reads.
 *
 * <p>A product is created ACTIVE with an upper-case, tenant-unique code. The
 * only mutation is the status toggle between ACTIVE and RETIRED; replaying the
 * current status is an idempotent no-op and writes no event. Every accepted
 * create and status change writes the product row and its
 * {@code product.product.created} or {@code product.product.status-changed}
 * outbox event in one transaction, so a rollback leaves neither behind.</p>
 */
@Service
public class ProductService {
    private final ProductRepository repository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ProductService(ProductRepository repository, OutboxRepository outbox) {
        this(repository, outbox, Clock.systemUTC());
    }

    ProductService(ProductRepository repository, OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates an ACTIVE product. Validation and normalization run before the
     * insert, so a rejected body never leaves data behind; a tenant-duplicate
     * code is a canonical 409 and writes no event.
     */
    @Transactional
    public ProductView create(UUID tenantId, CreateProductRequest request) {
        ProductRules.ProductSpec spec = ProductRules.validateCreate(request);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Product product = new Product(UUID.randomUUID(), tenantId, spec.productCode(),
                spec.productName(), spec.productLine(), spec.jurisdiction(),
                ProductRules.ACTIVE, 0, now, now);
        try {
            repository.insert(product);
        } catch (DuplicateKeyException ex) {
            String message = "Product code already exists for this tenant: "
                    + spec.productCode();
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT_CODE", message,
                    List.of(new ApiException.FieldIssue("productCode", "DUPLICATE_CODE",
                            message)));
        }
        outbox.insert(ProductEvents.created(product, now));
        return ProductView.of(product);
    }

    /**
     * Lists the tenant's products. {@code q} is a case-insensitive substring of
     * the code or name; {@code status} is ACTIVE or RETIRED. Both filters are
     * optional and combine with AND.
     */
    public List<ProductView> list(UUID tenantId, String q, String status) {
        String query = q == null || q.isBlank() ? null : q.trim();
        String statusFilter = status == null || status.isBlank()
                ? null : ProductRules.normalizeStatus(status);
        return repository.findAll(tenantId, query, statusFilter).stream()
                .map(ProductView::of)
                .toList();
    }

    public ProductView get(UUID tenantId, UUID productId) {
        return ProductView.of(requireProduct(tenantId, productId));
    }

    /**
     * Retires or reactivates a product. The update is conditional on the status
     * and version read by this command, so a concurrent winner is a canonical
     * 409 rather than a lost update. Setting the current status again returns
     * the unchanged product without an event.
     */
    @Transactional
    public ProductView updateStatus(UUID tenantId, UUID productId,
                                    UpdateProductStatusRequest request) {
        Product current = requireProduct(tenantId, productId);
        String target = ProductRules.normalizeStatus(request == null ? null : request.status());
        if (target.equals(current.status())) {
            return ProductView.of(current);
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = repository.updateStatus(tenantId, productId, target, current.status(),
                current.version(), Timestamp.from(now));
        if (rows == 0) {
            Product latest = requireProduct(tenantId, productId);
            throw conflict(latest.version(), current.version());
        }
        Product updated = requireProduct(tenantId, productId);
        outbox.insert(ProductEvents.statusChanged(current, updated, now));
        return ProductView.of(updated);
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return repository.findById(tenantId, productId)
                .orElseThrow(() -> PolicyErrors.notFound("PRODUCT_NOT_FOUND", "productId",
                        "Product not found: " + productId));
    }

    private static ApiException conflict(int currentVersion, int expectedVersion) {
        String message = "Expected version " + expectedVersion + " but product is at version "
                + currentVersion + "; reload and retry";
        return new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message,
                List.of(new ApiException.FieldIssue(
                        "version", "STALE_VERSION", message)),
                Map.of("currentVersion", currentVersion, "expectedVersion", expectedVersion));
    }
}
