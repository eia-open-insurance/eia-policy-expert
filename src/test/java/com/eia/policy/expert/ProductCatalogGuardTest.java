package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCatalogGuardTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PRODUCT_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final Instant CREATED = Instant.parse("2026-10-20T12:00:00Z");

    @Mock
    private ProductRepository repository;

    private ProductCatalogGuard guard() {
        return new ProductCatalogGuard(repository);
    }

    @Test
    void returnsAnActiveProductUsingTheNormalizedTenantScopedCode() {
        when(repository.findByCode(TENANT, "CGL"))
                .thenReturn(Optional.of(product("ACTIVE")));

        Product product = guard().requireActive(TENANT, "  cgl  ");

        assertThat(product.id()).isEqualTo(PRODUCT_ID);
        assertThat(product.status()).isEqualTo("ACTIVE");
        verify(repository).findByCode(TENANT, "CGL");
    }

    @Test
    void anUnknownCodeIsAnUnknownProductProblemWithTheCodeAndMeta() {
        when(repository.findByCode(TENANT, "NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requireActive(TENANT, " nope "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("UNKNOWN_PRODUCT");
                    assertThat(api.details()).singleElement().satisfies(issue -> {
                        assertThat(issue.field()).isEqualTo("productCode");
                        assertThat(issue.code()).isEqualTo("UNKNOWN_PRODUCT");
                    });
                    assertThat(api.meta())
                            .containsEntry("productCode", "NOPE")
                            .containsEntry("tenantId", TENANT.toString());
                });
    }

    @Test
    void anotherTenantProductIsIndistinguishableFromAnUnknownCode() {
        when(repository.findByCode(OTHER_TENANT, "CGL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requireActive(OTHER_TENANT, "CGL"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("UNKNOWN_PRODUCT"));
    }

    @Test
    void aRetiredProductIsAProductNotActiveProblemWithItsStatusInMeta() {
        when(repository.findByCode(TENANT, "CGL")).thenReturn(Optional.of(product("RETIRED")));

        assertThatThrownBy(() -> guard().requireActive(TENANT, "CGL"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("PRODUCT_NOT_ACTIVE");
                    assertThat(api.details()).singleElement().satisfies(issue -> {
                        assertThat(issue.field()).isEqualTo("productCode");
                        assertThat(issue.code()).isEqualTo("PRODUCT_NOT_ACTIVE");
                    });
                    assertThat(api.meta())
                            .containsEntry("productCode", "CGL")
                            .containsEntry("productId", PRODUCT_ID.toString())
                            .containsEntry("productStatus", "RETIRED")
                            .containsEntry("currentStatus", "RETIRED");
                });
    }

    @Test
    void aBlankCodeIsAnUnknownProductRatherThanARequestError() {
        when(repository.findByCode(TENANT, "")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requireActive(TENANT, "   "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("UNKNOWN_PRODUCT"));
    }

    private static Product product(String status) {
        return new Product(PRODUCT_ID, TENANT, "CGL", "Commercial General Liability", "CGL",
                "US", status, status.equals("ACTIVE") ? 0 : 1, CREATED, CREATED);
    }
}
