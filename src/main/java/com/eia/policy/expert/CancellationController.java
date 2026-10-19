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
public class CancellationController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final CancellationService service;

    public CancellationController(CancellationService service) {
        this.service = service;
    }

    @GetMapping("/cancellations")
    public ItemPage<PolicyCancellation> listCancellations(@RequestParam UUID tenantId,
                                                          @PathVariable UUID policyId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return service.listCancellations(tenantId, policyId, page, size);
    }

    @PostMapping("/cancel")
    public ResponseEntity<PolicyCancellation> cancel(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CancelPolicyRequest request) {
        CancellationResult result = service.cancel(tenantId, policyId, request, ifMatch,
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(Integer.toString(result.policy().version()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.cancellation());
    }

    @GetMapping("/reinstatements")
    public ItemPage<PolicyReinstatement> listReinstatements(@RequestParam UUID tenantId,
                                                            @PathVariable UUID policyId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return service.listReinstatements(tenantId, policyId, page, size);
    }

    @PostMapping("/reinstate")
    public ResponseEntity<PolicyReinstatement> reinstate(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReinstatePolicyRequest request) {
        ReinstatementResult result = service.reinstate(tenantId, policyId, request, ifMatch,
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(Integer.toString(result.policy().version()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.reinstatement());
    }
}
