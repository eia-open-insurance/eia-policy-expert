package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PRODUCT_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final Instant CREATED = Instant.parse("2026-10-21T12:00:00Z");
    private static final String CREATE_BODY = """
            {"productCode":"CGL","productName":"Commercial General Liability",
             "productLine":"CGL","jurisdiction":"US"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductService service;

    @Test
    void createsAProductUnderTheApiPrefix() throws Exception {
        when(service.create(eq(TENANT), any(CreateProductRequest.class)))
                .thenReturn(view("ACTIVE", 0));

        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.productCode").value("CGL"))
                .andExpect(jsonPath("$.productName").value("Commercial General Liability"))
                .andExpect(jsonPath("$.productLine").value("CGL"))
                .andExpect(jsonPath("$.jurisdiction").value("US"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:00:00Z"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        ArgumentCaptor<CreateProductRequest> captor =
                ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(service).create(eq(TENANT), captor.capture());
        assertThat(captor.getValue().productCode()).isEqualTo("CGL");
        assertThat(captor.getValue().productLine()).isEqualTo("CGL");
    }

    @Test
    void acceptsTheTenantHeaderAndShortBodyAliases() throws Exception {
        when(service.create(eq(TENANT), any(CreateProductRequest.class)))
                .thenReturn(view("ACTIVE", 0));

        mvc.perform(post("/api/v1/products")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"UMBRELLA","name":"Umbrella","line":"UMBRELLA",
                                 "jurisdiction":"US"}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateProductRequest> captor =
                ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(service).create(eq(TENANT), captor.capture());
        assertThat(captor.getValue().productCode()).isEqualTo("UMBRELLA");
        assertThat(captor.getValue().productName()).isEqualTo("Umbrella");
        assertThat(captor.getValue().productLine()).isEqualTo("UMBRELLA");
    }

    @Test
    void rejectsAMissingTenant() throws Exception {
        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_TENANT_HEADER"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonDirectBook() throws Exception {
        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsABlankCodeBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":" ","productName":"Product",
                                 "productLine":"CGL","jurisdiction":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"));

        verifyNoInteractions(service);
    }

    @Test
    void listsProductsWithSearchAndStatusFilters() throws Exception {
        when(service.list(TENANT, "liability", "ACTIVE")).thenReturn(List.of(view("ACTIVE", 0)));

        mvc.perform(get("/api/v1/products")
                        .param("tenantId", TENANT.toString())
                        .param("q", "liability")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$[0].productCode").value("CGL"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        verify(service).list(TENANT, "liability", "ACTIVE");
    }

    @Test
    void listsProductsUnderTheInternalAlias() throws Exception {
        when(service.list(TENANT, null, null)).thenReturn(List.of());

        mvc.perform(get("/internal/v1/products").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getsOneProduct() throws Exception {
        when(service.get(TENANT, PRODUCT_ID)).thenReturn(view("ACTIVE", 0));

        mvc.perform(get("/api/v1/products/{id}", PRODUCT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.productName").value("Commercial General Liability"));
    }

    @Test
    void returnsStructuredNotFoundForAnUnknownProduct() throws Exception {
        when(service.get(TENANT, PRODUCT_ID)).thenThrow(PolicyErrors.notFound(
                "PRODUCT_NOT_FOUND", "productId", "Product not found: " + PRODUCT_ID));

        mvc.perform(get("/api/v1/products/{id}", PRODUCT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
    }

    @Test
    void retiresAProductAndReturnsTheNewEtag() throws Exception {
        when(service.updateStatus(eq(TENANT), eq(PRODUCT_ID),
                any(UpdateProductStatusRequest.class)))
                .thenReturn(view("RETIRED", 1));

        mvc.perform(patch("/api/v1/products/{id}/status", PRODUCT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.version").value(1));

        ArgumentCaptor<UpdateProductStatusRequest> captor =
                ArgumentCaptor.forClass(UpdateProductStatusRequest.class);
        verify(service).updateStatus(eq(TENANT), eq(PRODUCT_ID), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("RETIRED");
    }

    @Test
    void rejectsABlankStatus() throws Exception {
        mvc.perform(patch("/api/v1/products/{id}/status", PRODUCT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));

        verifyNoInteractions(service);
    }

    private static ProductView view(String status, int version) {
        return new ProductView(PRODUCT_ID, "CGL", "Commercial General Liability", "CGL",
                "US", status, version, CREATED, CREATED);
    }
}
