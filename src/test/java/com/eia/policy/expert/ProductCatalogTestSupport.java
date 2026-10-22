package com.eia.policy.expert;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds the product catalog for the tenants and codes the shared integration
 * fixtures use. Policy and submission creates now require an ACTIVE catalog
 * product, so every H2 integration test that builds either aggregate starts
 * from a deterministic CGL/CGL_PRODUCTS catalog.
 *
 * <p>Seeding is per test method, so a test that retires a product cannot leak
 * that state into the next test. Each row is deleted and re-inserted for the
 * same {@code (tenant_id, product_code)} key.</p>
 */
abstract class ProductCatalogTestSupport {
    static final UUID CATALOG_TENANT =
            UUID.fromString("11111111-1111-7111-8111-111111111111");
    static final UUID CATALOG_OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    static final String CATALOG_PRODUCT_CODE = "CGL";
    static final String CATALOG_SUBMISSION_PRODUCT_CODE = "CGL_PRODUCTS";

    @Autowired
    private JdbcTemplate productCatalogJdbc;

    @BeforeEach
    void seedProductCatalog() {
        seedActiveProduct(CATALOG_TENANT, CATALOG_PRODUCT_CODE);
        seedActiveProduct(CATALOG_TENANT, CATALOG_SUBMISSION_PRODUCT_CODE);
        seedActiveProduct(CATALOG_OTHER_TENANT, CATALOG_PRODUCT_CODE);
        seedActiveProduct(CATALOG_OTHER_TENANT, CATALOG_SUBMISSION_PRODUCT_CODE);
    }

    /**
     * Inserts (or resets) one ACTIVE product for the tenant. Uses plain SQL so
     * it also works for the opt-in PostgreSQL smoke profile.
     */
    protected final void seedActiveProduct(UUID tenantId, String productCode) {
        Timestamp now = Timestamp.from(Instant.now());
        productCatalogJdbc.update(
                "DELETE FROM product WHERE tenant_id = ? AND product_code = ?",
                tenantId, productCode);
        productCatalogJdbc.update("""
                INSERT INTO product (id, tenant_id, product_code, product_name,
                                     product_line, jurisdiction, status, version,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, productCode, productCode + " product",
                "CGL", "US", "ACTIVE", 0, now, now);
    }
}
