package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy billing plan and installment API. The path is also exposed under the
 * service's internal prefix so the Policy domain adapter can reach the same
 * resource without a second implementation.
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/billing",
        "/internal/v1/policies/{policyId}/billing"
})
public class BillingController {
    private final BillingService service;

    public BillingController(BillingService service) {
        this.service = service;
    }

    @GetMapping
    public BillingPlanView get(@RequestParam UUID tenantId, @PathVariable UUID policyId) {
        return service.get(tenantId, policyId);
    }

    @PostMapping
    public ResponseEntity<BillingPlanView> create(@RequestParam UUID tenantId,
                                                  @PathVariable UUID policyId,
                                                  @Valid @RequestBody
                                                  CreateBillingPlanRequest request) {
        BillingPlanView view = service.create(tenantId, policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Appends a positive premium adjustment installment to the ACTIVE plan and
     * returns the updated plan, the new installment, and the adjustment facts.
     */
    @PostMapping("/adjustment")
    public ResponseEntity<BillingAdjustmentView> adjust(
            @RequestParam UUID tenantId,
            @PathVariable UUID policyId,
            @Valid @RequestBody BillingAdjustmentRequest request) {
        BillingAdjustmentView view = service.adjust(tenantId, policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PatchMapping("/installments/{installmentId}")
    public PolicyInstallment updateInstallment(@RequestParam UUID tenantId,
                                               @PathVariable UUID policyId,
                                               @PathVariable UUID installmentId,
                                               @Valid @RequestBody
                                               UpdateInstallmentRequest request) {
        return service.updateInstallment(tenantId, policyId, installmentId, request);
    }
}
