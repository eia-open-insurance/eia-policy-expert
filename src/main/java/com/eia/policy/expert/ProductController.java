package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Product catalog API.
 *
 * <p>The public paths are also exposed under the service's internal prefix so
 * the Policy domain adapter can reach the same resources. The tenant comes from
 * {@code ?tenantId=} or {@code X-EIA-Tenant}; the route is fixed to the DIRECT
 * book, so another {@code X-EIA-Book} is a 404.</p>
 */
@RestController
@RequestMapping({"/api/v1/products", "/internal/v1/products"})
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductView> list(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        RequestScope.requireDirectBook(book);
        return service.list(RequestScope.tenant(tenantId, tenantHeader), q, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> get(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        ProductView product = service.get(RequestScope.tenant(tenantId, tenantHeader), id);
        return ResponseEntity.ok()
                .eTag(Integer.toString(product.version()))
                .body(product);
    }

    @PostMapping
    public ResponseEntity<ProductView> create(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody CreateProductRequest request) {
        RequestScope.requireDirectBook(book);
        ProductView created = service.create(
                RequestScope.tenant(tenantId, tenantHeader), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Integer.toString(created.version()))
                .body(created);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ProductView> updateStatus(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody UpdateProductStatusRequest request) {
        RequestScope.requireDirectBook(book);
        ProductView updated = service.updateStatus(
                RequestScope.tenant(tenantId, tenantHeader), id, request);
        return ResponseEntity.ok()
                .eTag(Integer.toString(updated.version()))
                .body(updated);
    }
}
