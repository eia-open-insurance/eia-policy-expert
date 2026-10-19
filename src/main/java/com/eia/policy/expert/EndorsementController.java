package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/policies/{policyId}")
public class EndorsementController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final EndorsementService service;

    public EndorsementController(EndorsementService service) {
        this.service = service;
    }

    @GetMapping("/endorsements")
    public ItemPage<PolicyEndorsement> list(@RequestParam UUID tenantId,
                                            @PathVariable UUID policyId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return service.list(tenantId, policyId, page, size);
    }

    @GetMapping("/endorsements/{endorsementId}")
    public PolicyEndorsement get(@RequestParam UUID tenantId, @PathVariable UUID policyId,
                                 @PathVariable UUID endorsementId) {
        return service.get(tenantId, policyId, endorsementId);
    }

    @PostMapping("/endorsements")
    public ResponseEntity<PolicyEndorsement> create(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateEndorsementRequest request) {
        EndorsementResult result = service.create(tenantId, policyId, request, ifMatch,
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(Integer.toString(result.policyVersion()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.endorsement());
    }
}
