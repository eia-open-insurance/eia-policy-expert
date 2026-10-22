package com.eia.policy.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo product seed: the three baseline products land once for the demo tenant,
 * a restart is a no-op, and an operator edit is never overwritten. The test
 * profile disables the automatic runner, so each test drives the seeder
 * explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductSeedTest {
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private ProductSeeder seeder;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM product");
    }

    @Test
    void seedsTheThreeDemoProductsOnce() {
        assertThat(seeder.seedDemoTenant()).isEqualTo(3);
        assertThat(seeder.seedDemoTenant()).isZero();

        List<Product> products = repository.findAll(ProductSeeder.DEMO_TENANT, null, null);
        assertThat(products).hasSize(3);
        assertThat(products).extracting(Product::productCode)
                .containsExactly("CGL", "PRODUCTS_LIABILITY", "UMBRELLA");

        Map<String, Product> byCode = products.stream()
                .collect(Collectors.toMap(Product::productCode, Function.identity()));
        assertDemoProduct(byCode.get("CGL"), "Commercial General Liability", "CGL");
        assertDemoProduct(byCode.get("PRODUCTS_LIABILITY"), "Products Liability",
                "PRODUCTS_LIABILITY");
        assertDemoProduct(byCode.get("UMBRELLA"), "Umbrella", "UMBRELLA");

        for (Product product : products) {
            assertThat(product.tenantId()).isEqualTo(ProductSeeder.DEMO_TENANT);
            assertThat(product.jurisdiction()).isEqualTo("US");
            assertThat(product.status()).isEqualTo("ACTIVE");
            assertThat(product.version()).isZero();
            assertThat(product.createdAt()).isNotNull();
            assertThat(product.updatedAt()).isNotNull();
        }

        // Seed rows are bootstrap data, not business commands: no events.
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void doesNotOverwriteAnExistingDemoProduct() {
        Product existing = new Product(UUID.randomUUID(), ProductSeeder.DEMO_TENANT, "CGL",
                "Operator CGL", "OTHER", "GB", "RETIRED", 4, NOW, NOW);
        repository.insert(existing);

        assertThat(seeder.seedDemoTenant()).isEqualTo(2);

        Product cgl = repository.findByCode(ProductSeeder.DEMO_TENANT, "CGL").orElseThrow();
        assertThat(cgl.id()).isEqualTo(existing.id());
        assertThat(cgl.productName()).isEqualTo("Operator CGL");
        assertThat(cgl.productLine()).isEqualTo("OTHER");
        assertThat(cgl.jurisdiction()).isEqualTo("GB");
        assertThat(cgl.status()).isEqualTo("RETIRED");
        assertThat(cgl.version()).isEqualTo(4);
        assertThat(count("product")).isEqualTo(3);
    }

    @Test
    void seedsOnlyTheDemoTenant() {
        seeder.seedDemoTenant();

        assertThat(repository.findAll(OTHER_TENANT, null, null)).isEmpty();
        assertThat(repository.findAll(ProductSeeder.DEMO_TENANT, null, null)).hasSize(3);
    }

    @Test
    void theStartupRunnerSeedsIdempotently() {
        ProductSeedRunner runner = new ProductSeedRunner(seeder);

        runner.run(null);
        runner.run(null);

        assertThat(count("product")).isEqualTo(3);
    }

    private static void assertDemoProduct(Product product, String name, String line) {
        assertThat(product).isNotNull();
        assertThat(product.productName()).isEqualTo(name);
        assertThat(product.productLine()).isEqualTo(line);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
