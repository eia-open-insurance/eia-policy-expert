package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductRulesTest {
    @Test
    void normalizesCodeNameLineAndJurisdiction() {
        ProductRules.ProductSpec spec = ProductRules.validateCreate(new CreateProductRequest(
                "  cgl  ", "  Commercial General Liability  ", " cgl ", " us "));

        assertThat(spec.productCode()).isEqualTo("CGL");
        assertThat(spec.productName()).isEqualTo("Commercial General Liability");
        assertThat(spec.productLine()).isEqualTo("CGL");
        assertThat(spec.jurisdiction()).isEqualTo("US");
    }

    @Test
    void acceptsEveryProductLine() {
        for (String line : new String[]{
                "CGL", "PRODUCTS_LIABILITY", "UMBRELLA", "EXCESS", "OTHER"}) {
            assertThat(ProductRules.validateCreate(new CreateProductRequest(
                    "P-" + line, "Product " + line, line.toLowerCase(), "US")).productLine())
                    .isEqualTo(line);
        }
    }

    @Test
    void rejectsABlankCode() {
        assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                "  ", "Product", "CGL", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_PRODUCT_CODE");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("productCode");
                });
    }

    @Test
    void rejectsAnOverlongCode() {
        assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                "X".repeat(33), "Product", "CGL", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_CODE"));
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                "CGL", "   ", "CGL", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_NAME"));
    }

    @Test
    void rejectsAnOverlongName() {
        assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                "CGL", "N".repeat(201), "CGL", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_NAME"));
    }

    @Test
    void rejectsAnUnknownProductLine() {
        assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                "CGL", "Product", "PROPERTY", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_PRODUCT_LINE");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("productLine");
                });
    }

    @Test
    void rejectsANonAlpha2Jurisdiction() {
        for (String jurisdiction : new String[]{"U", "USA", "1A", "", null}) {
            assertThatThrownBy(() -> ProductRules.validateCreate(new CreateProductRequest(
                    "CGL", "Product", "CGL", jurisdiction)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).code())
                            .isEqualTo("INVALID_PRODUCT_JURISDICTION"));
        }
    }

    @Test
    void normalizesAndRejectsUnknownStatuses() {
        assertThat(ProductRules.normalizeStatus(" retired ")).isEqualTo("RETIRED");
        assertThat(ProductRules.normalizeStatus("active")).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> ProductRules.normalizeStatus("SUSPENDED"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_STATUS"));
    }

    @Test
    void rejectsANullRequest() {
        assertThatThrownBy(() -> ProductRules.validateCreate(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT"));
    }
}
