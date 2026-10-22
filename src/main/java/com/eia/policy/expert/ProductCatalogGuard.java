package com.eia.policy.expert;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * New-business guard for the product catalog.
 *
 * <p>A policy or underwriting submission may only be created for a product
 * code that exists for the tenant and is {@code ACTIVE}. The lookup follows the
 * catalog contract: the request code is trimmed and upper-cased, so
 * {@code cgl} and {@code CGL} address the same tenant-unique row. An unknown
 * code is {@code 422 UNKNOWN_PRODUCT}; a known but {@code RETIRED} code is
 * {@code 422 PRODUCT_NOT_ACTIVE}. Both problems identify the
 * {@code productCode} field and carry the offending code plus the tenant (and,
 * when the row exists, its id and status) in {@code meta}.</p>
 *
 * <p>The guard is deliberately only for new business. Existing policies and
 * submissions are never re-validated, and commands that derive a new term from
 * an accepted aggregate (submission conversion, renewal) do not call it, so
 * retiring a product never invalidates work that was already accepted.</p>
 */
@Component
public class ProductCatalogGuard {
    private final ProductRepository repository;

    public ProductCatalogGuard(ProductRepository repository) {
        this.repository = repository;
    }

    /**
     * Requires an ACTIVE catalog product for the tenant and returns it.
     *
     * @throws ApiException {@code 422 UNKNOWN_PRODUCT} when the code is not in
     *                      the tenant catalog, or {@code 422 PRODUCT_NOT_ACTIVE}
     *                      when it exists but is RETIRED
     */
    public Product requireActive(UUID tenantId, String rawProductCode) {
        String productCode = normalizeLookupCode(rawProductCode);
        Product product = repository.findByCode(tenantId, productCode)
                .orElseThrow(() -> unknownProduct(tenantId, productCode));
        if (!ProductRules.ACTIVE.equals(product.status())) {
            throw productNotActive(product);
        }
        return product;
    }

    /**
     * Applies the catalog's normalization to a request code: trim surrounding
     * whitespace and upper-case. Blank stays blank and is therefore an unknown
     * code rather than a validation error.
     */
    static String normalizeLookupCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static ApiException unknownProduct(UUID tenantId, String productCode) {
        String message = "Product code " + productCode + " does not exist in this tenant's "
                + "product catalog.";
        return PolicyErrors.businessRule("UNKNOWN_PRODUCT", "productCode", message,
                Map.of("productCode", productCode, "tenantId", tenantId.toString()));
    }

    private static ApiException productNotActive(Product product) {
        String message = "Product code " + product.productCode() + " is " + product.status()
                + "; only an ACTIVE product can be used for new business.";
        return PolicyErrors.businessRule("PRODUCT_NOT_ACTIVE", "productCode", message,
                Map.of("productCode", product.productCode(),
                        "productId", product.id().toString(),
                        "productStatus", product.status(),
                        "currentStatus", product.status(),
                        "tenantId", product.tenantId().toString()));
    }
}
