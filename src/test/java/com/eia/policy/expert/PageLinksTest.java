package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PageLinksTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final Map<String, String> FILTERS = Map.of("tenantId", TENANT.toString());

    @Test
    void singlePageHasSelfOnly() {
        PolicyPage.Links links = PageLinks.build("/internal/v1/policies", FILTERS, 0, 20, 20);
        assertThat(links.self().href())
                .isEqualTo("/internal/v1/policies?tenantId=" + TENANT + "&page=0&size=20");
        assertThat(links.next()).isNull();
        assertThat(links.prev()).isNull();
    }

    @Test
    void firstPageOfManyHasNextOnly() {
        PolicyPage.Links links = PageLinks.build("/internal/v1/policies", FILTERS, 0, 20, 21);
        assertThat(links.next().href())
                .isEqualTo("/internal/v1/policies?tenantId=" + TENANT + "&page=1&size=20");
        assertThat(links.prev()).isNull();
    }

    @Test
    void interiorPageHasBothLinks() {
        PolicyPage.Links links = PageLinks.build("/internal/v1/policies", FILTERS, 1, 20, 41);
        assertThat(links.next().href())
                .isEqualTo("/internal/v1/policies?tenantId=" + TENANT + "&page=2&size=20");
        assertThat(links.prev().href())
                .isEqualTo("/internal/v1/policies?tenantId=" + TENANT + "&page=0&size=20");
    }

    @Test
    void lastPageHasPreviousOnly() {
        PolicyPage.Links links = PageLinks.build("/internal/v1/policies", FILTERS, 2, 20, 41);
        assertThat(links.next()).isNull();
        assertThat(links.prev().href())
                .isEqualTo("/internal/v1/policies?tenantId=" + TENANT + "&page=1&size=20");
    }

    @Test
    void emptyTenantHasNoBoundaryLinks() {
        PolicyPage.Links links = PageLinks.build("/internal/v1/policies", FILTERS, 0, 20, 0);
        assertThat(links.self()).isNotNull();
        assertThat(links.next()).isNull();
        assertThat(links.prev()).isNull();
    }
}
