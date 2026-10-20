package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy mass tort profile API. The public path is also exposed under the
 * service's internal prefix so the Policy domain adapter can reach the same
 * resource. The tenant comes from {@code ?tenantId=} or {@code X-EIA-Tenant};
 * the route is fixed to the DIRECT book, so another {@code X-EIA-Book} is a
 * 404.
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/mass-tort-profile",
        "/internal/v1/policies/{policyId}/mass-tort-profile"
})
public class MassTortProfileController {
    private final MassTortProfileService service;

    public MassTortProfileController(MassTortProfileService service) {
        this.service = service;
    }

    @GetMapping
    public PolicyMassTortProfile get(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.get(RequestScope.tenant(tenantId, tenantHeader), policyId);
    }

    /**
     * Upserts the profile: {@code 201} when the row was created, {@code 200}
     * when an existing row was updated.
     */
    @PutMapping
    public ResponseEntity<PolicyMassTortProfile> upsert(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody PutMassTortProfileRequest request) {
        RequestScope.requireDirectBook(book);
        MassTortProfileResult result = service.upsert(
                RequestScope.tenant(tenantId, tenantHeader), policyId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.profile());
    }
}
