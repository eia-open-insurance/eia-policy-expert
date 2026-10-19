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

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/policies")
public class PolicyController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    public PolicyPage list(@RequestParam UUID tenantId,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return service.list(tenantId, page, size);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Policy> get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        Policy policy = service.get(tenantId, id);
        return ResponseEntity.ok()
                .eTag(Integer.toString(policy.version()))
                .body(policy);
    }

    @PostMapping
    public ResponseEntity<Policy> create(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePolicyRequest request) {
        CreateResult result = service.create(request, idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .eTag(Integer.toString(result.policy().version()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.policy());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Policy> updateStatus(
            @RequestParam UUID tenantId,
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdatePolicyStatusRequest request) {
        Policy policy = service.updateStatus(tenantId, id, request, ifMatch);
        return ResponseEntity.ok()
                .eTag(Integer.toString(policy.version()))
                .body(policy);
    }
}
