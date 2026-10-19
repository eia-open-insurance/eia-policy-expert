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
public class CoverageController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    @GetMapping("/coverages")
    public ItemPage<CoverageView> listCoverages(@RequestParam UUID tenantId,
                                                @PathVariable UUID policyId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listCoverages(tenantId, policyId, page, size);
    }

    @PostMapping("/coverages")
    public ResponseEntity<CoverageView> createCoverage(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateCoverageRequest request) {
        CoverageResult result = service.create(tenantId, policyId, request, ifMatch, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(Integer.toString(result.policyVersion()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.coverage());
    }

    @GetMapping("/limits")
    public ItemPage<PolicyLimit> listLimits(@RequestParam UUID tenantId,
                                            @PathVariable UUID policyId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return service.listLimits(tenantId, policyId, page, size);
    }

    @GetMapping("/aggregate-state")
    public ItemPage<PolicyLimitState> aggregateState(@RequestParam UUID tenantId,
                                                     @PathVariable UUID policyId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.aggregateState(tenantId, policyId, page, size);
    }

    @PostMapping("/erosion")
    public ResponseEntity<PolicyLimitState> applyErosion(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ErosionRequest request) {
        ErosionResult result = service.applyErosion(tenantId, policyId, request, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(Integer.toString(result.policyVersion()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(result.state());
    }
}
