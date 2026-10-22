package com.eia.policy.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end product catalog flow on H2: create/list/search/get, retirement and
 * reactivation, the tenant wall, duplicate-code arbitration, the two outbox
 * events, and rollback safety.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of("outbox_event", "product")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsListsSearchesAndGetsProducts() throws Exception {
        UUID cgl = createProduct(TENANT, " cgl ", " Commercial General Liability ", " cgl ",
                "us");
        UUID umbrella = createProduct(TENANT, "UMBRELLA", "Umbrella", "UMBRELLA", "US");

        mvc.perform(get("/api/v1/products").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productCode").value("CGL"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].productCode").value("UMBRELLA"));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("q", "commercial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(cgl.toString()));

        mvc.perform(get("/api/v1/products")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("q", "umb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(umbrella.toString()));

        mvc.perform(get("/internal/v1/products/{id}", cgl)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.productId").value(cgl.toString()))
                .andExpect(jsonPath("$.productName").value("Commercial General Liability"))
                .andExpect(jsonPath("$.productLine").value("CGL"))
                .andExpect(jsonPath("$.jurisdiction").value("US"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(patch("/api/v1/products/{id}/status", cgl)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("RETIRED"));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("status", "RETIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(cgl.toString()));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(umbrella.toString()));
    }

    @Test
    void enforcesTheTenantWallOnEveryReadAndCommand() throws Exception {
        UUID productId = createProduct(TENANT, "CGL", "Commercial General Liability", "CGL",
                "US");

        mvc.perform(get("/api/v1/products").param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(get("/api/v1/products/{id}", productId)
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("q", "commercial")
                        .param("status", "RETIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // The unique code is tenant-scoped: the same code is a separate row for
        // another tenant and never appears in this tenant's reads.
        UUID otherProductId = createProduct(OTHER_TENANT, "CGL", "Other Tenant CGL", "CGL",
                "US");
        mvc.perform(get("/api/v1/products").param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(otherProductId.toString()));
        mvc.perform(get("/api/v1/products").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(productId.toString()));

        Product unchanged = productRepository.findById(TENANT, productId).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("ACTIVE");
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void rejectsDuplicateCodesCaseInsensitively() throws Exception {
        createProduct(TENANT, "CGL", "Commercial General Liability", "CGL", "US");
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"cgl","productName":"Another CGL",
                                 "productLine":"CGL","jurisdiction":"US"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_CODE"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"));

        assertThat(count("product")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void retiresReactivatesAndWritesAStatusEventPerTransition() throws Exception {
        UUID productId = createProduct(TENANT, "CGL", "Commercial General Liability", "CGL",
                "US");

        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        // Replaying the current status is a no-op: it returns the same row and
        // writes no additional event.
        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_STATUS"));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, partition_key, payload,
                       headers
                  FROM outbox_event
                 WHERE event_type LIKE 'product.product.%'
                 ORDER BY event_sequence
                """);
        assertThat(events).hasSize(3);
        assertThat(events).extracting(event -> event.get("event_type"))
                .containsExactly("product.product.created",
                        "product.product.status-changed",
                        "product.product.status-changed");
        assertThat(events).extracting(event -> event.get("aggregate_type"))
                .containsOnly("Product");
        assertThat(events).extracting(event -> event.get("aggregate_version"))
                .containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(event -> event.get("partition_key"))
                .containsOnly(productId.toString());
        assertThat((String) events.get(0).get("payload"))
                .contains("\"productId\":\"" + productId + "\"")
                .contains("\"productCode\":\"CGL\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"version\":0");
        assertThat((String) events.get(1).get("payload"))
                .contains("\"previousStatus\":\"ACTIVE\"")
                .contains("\"status\":\"RETIRED\"")
                .contains("\"version\":1")
                .contains("\"changedFields\":[\"status\"]");
        assertThat((String) events.get(2).get("payload"))
                .contains("\"previousStatus\":\"RETIRED\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"version\":2");
        assertThat(events).allSatisfy(event -> assertThat((String) event.get("headers"))
                .contains("\"vertical\":\"product\"")
                .contains("\"book\":\"DIRECT\""));
    }

    @Test
    void validatesTheCatalogFieldsAndWritesNothingForARejectedBody() throws Exception {
        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"CGL","productName":"Product",
                                 "productLine":"PROPERTY","jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_LINE"))
                .andExpect(jsonPath("$.errors[0].field").value("productLine"));

        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"CGL","productName":"Product",
                                 "productLine":"CGL","jurisdiction":"USA"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productCode\":\"" + "X".repeat(33)
                                + "\",\"productName\":\"Product\",\"productLine\":\"CGL\","
                                + "\"jurisdiction\":\"US\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"CGL","productName":" ",
                                 "productLine":"CGL","jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("productName"));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("status", "SUSPENDED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_STATUS"));

        assertThat(count("product")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void productCreateRollbackLeavesNoRowAndNoEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            productService.create(TENANT, new CreateProductRequest(
                    "ROLLBACK", "Rollback Product", "CGL", "US"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("product")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    private UUID createProduct(UUID tenantId, String code, String name, String line,
                               String jurisdiction) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/products")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productCode\":\"" + code + "\",\"productName\":\""
                                + name + "\",\"productLine\":\"" + line
                                + "\",\"jurisdiction\":\"" + jurisdiction + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("productId").asText());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
