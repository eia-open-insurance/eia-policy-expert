package com.eia.policy.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent demo seed for the product catalog.
 *
 * <p>The demo tenant gets the three baseline product lines the UI and smoke
 * flows expect: CGL, products liability, and umbrella, all in the US. Seeding
 * is keyed by {@code (tenant_id, product_code)} and never overwrites an
 * existing row, so a restart (or an operator edit) is safe. Seeded rows are
 * catalog bootstrap data, not business commands, so they write no outbox
 * events.</p>
 */
@Component
public class ProductSeeder {
    static final UUID DEMO_TENANT =
            UUID.fromString("11111111-1111-7111-8111-111111111111");

    static final List<SeedProduct> DEMO_PRODUCTS = List.of(
            new SeedProduct(ProductRules.CGL, "Commercial General Liability",
                    ProductRules.CGL, "US"),
            new SeedProduct(ProductRules.PRODUCTS_LIABILITY, "Products Liability",
                    ProductRules.PRODUCTS_LIABILITY, "US"),
            new SeedProduct(ProductRules.UMBRELLA, "Umbrella",
                    ProductRules.UMBRELLA, "US"));

    private final ProductRepository repository;
    private final Clock clock;

    @Autowired
    public ProductSeeder(ProductRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ProductSeeder(ProductRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Inserts the demo products that are missing and returns the number of rows
     * written. Re-running is a no-op and returns zero once all three exist.
     */
    @Transactional
    public int seedDemoTenant() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int inserted = 0;
        for (SeedProduct seed : DEMO_PRODUCTS) {
            Product product = new Product(UUID.randomUUID(), DEMO_TENANT, seed.code(),
                    seed.name(), seed.line(), seed.jurisdiction(), ProductRules.ACTIVE, 0,
                    now, now);
            inserted += repository.insertIfAbsent(product);
        }
        return inserted;
    }

    record SeedProduct(String code, String name, String line, String jurisdiction) {
    }
}
