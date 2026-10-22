package com.eia.policy.expert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the demo product seed after the context is ready. The seed can be
 * disabled with {@code eia.product.seed.enabled=false} (the test profile does
 * that so component tests control the catalog themselves).
 */
@Component
@ConditionalOnProperty(name = "eia.product.seed.enabled", havingValue = "true",
        matchIfMissing = true)
public class ProductSeedRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ProductSeedRunner.class);

    private final ProductSeeder seeder;

    public ProductSeedRunner(ProductSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int inserted = seeder.seedDemoTenant();
        if (inserted > 0) {
            LOG.info("Seeded {} demo product(s) for tenant {}", inserted,
                    ProductSeeder.DEMO_TENANT);
        }
    }
}
