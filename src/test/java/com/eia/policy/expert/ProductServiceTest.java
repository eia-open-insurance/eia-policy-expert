package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PRODUCT_ID =
            UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-20T12:00:00Z");

    @Mock
    private ProductRepository repository;

    @Mock
    private OutboxRepository outbox;

    private ProductService service() {
        return new ProductService(repository, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnActiveProductWithNormalizedFieldsAndACreatedEvent() {
        ProductView view = service().create(TENANT, new CreateProductRequest(
                "  cgl  ", "  Commercial General Liability  ", " cgl ", " us "));

        assertThat(view.productId()).isNotNull();
        assertThat(view.productCode()).isEqualTo("CGL");
        assertThat(view.productName()).isEqualTo("Commercial General Liability");
        assertThat(view.productLine()).isEqualTo("CGL");
        assertThat(view.jurisdiction()).isEqualTo("US");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW);

        ArgumentCaptor<Product> inserted = ArgumentCaptor.forClass(Product.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(inserted.getValue().status()).isEqualTo("ACTIVE");
        assertThat(inserted.getValue().version()).isZero();

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("product.product.created");
        assertThat(event.aggregateType()).isEqualTo("Product");
        assertThat(event.aggregateId()).isEqualTo(view.productId());
        assertThat(event.aggregateVersion()).isEqualTo(1L);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.partitionKey()).isEqualTo(view.productId().toString());
        assertThat(event.headers()).contains("\"vertical\":\"product\"")
                .contains("\"book\":\"DIRECT\"");
        assertThat(event.payload())
                .contains("\"productId\":\"" + view.productId() + "\"")
                .contains("\"productCode\":\"CGL\"")
                .contains("\"productName\":\"Commercial General Liability\"")
                .contains("\"productLine\":\"CGL\"")
                .contains("\"jurisdiction\":\"US\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"version\":0")
                .contains("\"createdAt\":\"2026-10-21T12:00:00Z\"");
    }

    @Test
    void duplicateCodeIsAConflictWithoutAnEvent() {
        doThrow(new DuplicateKeyException("duplicate")).when(repository)
                .insert(any(Product.class));

        assertThatThrownBy(() -> service().create(TENANT, new CreateProductRequest(
                "CGL", "Commercial General Liability", "CGL", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_PRODUCT_CODE");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("productCode");
                });

        verifyNoInteractions(outbox);
    }

    @Test
    void invalidCreateDoesNotWriteARowOrAnEvent() {
        assertThatThrownBy(() -> service().create(TENANT, new CreateProductRequest(
                "CGL", "Commercial General Liability", "PROPERTY", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_LINE"));

        verifyNoInteractions(repository, outbox);
    }

    @Test
    void listNormalizesTheFiltersAndMapsTheRows() {
        when(repository.findAll(TENANT, "liability", "ACTIVE"))
                .thenReturn(List.of(product("ACTIVE", 0, CREATED)));

        assertThat(service().list(TENANT, "  liability  ", " active "))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.productId()).isEqualTo(PRODUCT_ID);
                    assertThat(view.productCode()).isEqualTo("CGL");
                    assertThat(view.status()).isEqualTo("ACTIVE");
                });

        verify(repository).findAll(TENANT, "liability", "ACTIVE");
    }

    @Test
    void blankFiltersBecomeNull() {
        when(repository.findAll(TENANT, null, null)).thenReturn(List.of());

        assertThat(service().list(TENANT, "   ", "")).isEmpty();

        verify(repository).findAll(TENANT, null, null);
    }

    @Test
    void listRejectsAnUnknownStatusFilter() {
        assertThatThrownBy(() -> service().list(TENANT, null, "SUSPENDED"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_STATUS"));

        verifyNoInteractions(repository);
    }

    @Test
    void getsAProductByTenantAndId() {
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(product("ACTIVE", 0, CREATED)));

        assertThat(service().get(TENANT, PRODUCT_ID).productId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void aProductFromAnotherTenantIsNotFound() {
        when(repository.findById(OTHER_TENANT, PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(OTHER_TENANT, PRODUCT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("PRODUCT_NOT_FOUND");
                });
    }

    @Test
    void retiresAProductAndWritesAStatusChangedEvent() {
        Product active = product("ACTIVE", 0, CREATED);
        Product retired = product("RETIRED", 1, NOW);
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(active))
                .thenReturn(Optional.of(retired));
        when(repository.updateStatus(eq(TENANT), eq(PRODUCT_ID), eq("RETIRED"), eq("ACTIVE"),
                eq(0), any(Timestamp.class))).thenReturn(1);

        ProductView view = service().updateStatus(TENANT, PRODUCT_ID,
                new UpdateProductStatusRequest(" retired "));

        assertThat(view.status()).isEqualTo("RETIRED");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.updatedAt()).isEqualTo(NOW);

        OutboxEvent event = onlyEvent();
        assertThat(event.eventType()).isEqualTo("product.product.status-changed");
        assertThat(event.aggregateType()).isEqualTo("Product");
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.payload())
                .contains("\"previousStatus\":\"ACTIVE\"")
                .contains("\"status\":\"RETIRED\"")
                .contains("\"version\":1")
                .contains("\"changedFields\":[\"status\"]");
    }

    @Test
    void reactivatesAProductAndAdvancesTheEventVersion() {
        Product retired = product("RETIRED", 1, CREATED);
        Product active = product("ACTIVE", 2, NOW);
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(retired))
                .thenReturn(Optional.of(active));
        when(repository.updateStatus(eq(TENANT), eq(PRODUCT_ID), eq("ACTIVE"), eq("RETIRED"),
                eq(1), any(Timestamp.class))).thenReturn(1);

        ProductView view = service().updateStatus(TENANT, PRODUCT_ID,
                new UpdateProductStatusRequest("ACTIVE"));

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.version()).isEqualTo(2);

        OutboxEvent event = onlyEvent();
        assertThat(event.aggregateVersion()).isEqualTo(3L);
        assertThat(event.payload()).contains("\"previousStatus\":\"RETIRED\"");
    }

    @Test
    void replayingTheCurrentStatusIsANoOpWithoutAnEvent() {
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(product("ACTIVE", 0, CREATED)));

        ProductView view = service().updateStatus(TENANT, PRODUCT_ID,
                new UpdateProductStatusRequest("active"));

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.version()).isZero();

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void anUnknownTargetStatusIsARequestError() {
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(product("ACTIVE", 0, CREATED)));

        assertThatThrownBy(() -> service().updateStatus(TENANT, PRODUCT_ID,
                new UpdateProductStatusRequest("SUSPENDED")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PRODUCT_STATUS"));

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentStatusChangeIsAConflictAndWritesNoEvent() {
        Product active = product("ACTIVE", 0, CREATED);
        Product winner = product("RETIRED", 1, NOW);
        when(repository.findById(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(active))
                .thenReturn(Optional.of(winner));
        when(repository.updateStatus(eq(TENANT), eq(PRODUCT_ID), eq("RETIRED"), eq("ACTIVE"),
                eq(0), any(Timestamp.class))).thenReturn(0);

        assertThatThrownBy(() -> service().updateStatus(TENANT, PRODUCT_ID,
                new UpdateProductStatusRequest("RETIRED")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta())
                            .containsEntry("currentVersion", 1)
                            .containsEntry("expectedVersion", 0);
                });

        verifyNoInteractions(outbox);
    }

    private OutboxEvent onlyEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static Product product(String status, int version, Instant updatedAt) {
        return new Product(PRODUCT_ID, TENANT, "CGL", "Commercial General Liability", "CGL",
                "US", status, version, CREATED, updatedAt);
    }
}
