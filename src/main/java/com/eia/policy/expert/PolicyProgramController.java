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
 * Commercial program catalog API plus the policy-to-program assignment command.
 *
 * <p>The public paths are also exposed under the service's internal prefix so
 * the Policy domain adapter can reach the same resources. The tenant comes from
 * {@code ?tenantId=} or {@code X-EIA-Tenant}; the route is fixed to the DIRECT
 * book, so another {@code X-EIA-Book} is a 404. {@code PATCH} with a null
 * {@code programId} clears the policy link.</p>
 */
@RestController
@RequestMapping({"/api/v1", "/internal/v1"})
public class PolicyProgramController {
    private final PolicyProgramService service;

    public PolicyProgramController(PolicyProgramService service) {
        this.service = service;
    }

    @PostMapping("/programs")
    public ResponseEntity<ProgramView> create(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody CreateProgramRequest request) {
        RequestScope.requireDirectBook(book);
        ProgramView created = service.create(
                RequestScope.tenant(tenantId, tenantHeader), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/programs")
    public List<ProgramView> list(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.list(RequestScope.tenant(tenantId, tenantHeader));
    }

    @GetMapping("/programs/{id}")
    public ProgramView get(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.get(RequestScope.tenant(tenantId, tenantHeader), id);
    }

    @PatchMapping("/policies/{policyId}/program")
    public ResponseEntity<Policy> assign(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestBody(required = false) AssignPolicyProgramRequest request) {
        RequestScope.requireDirectBook(book);
        Policy policy = service.assignProgram(
                RequestScope.tenant(tenantId, tenantHeader), policyId, request);
        return ResponseEntity.ok()
                .eTag(Integer.toString(policy.version()))
                .body(policy);
    }
}
