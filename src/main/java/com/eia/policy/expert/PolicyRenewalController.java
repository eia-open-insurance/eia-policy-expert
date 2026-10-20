package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Renewal command. The external path is {@code /api/v1}; the domain adapter
 * keeps the {@code /internal/v1} alias, matching the billing controller.
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/renew",
        "/internal/v1/policies/{policyId}/renew"
})
public class PolicyRenewalController {
    private final PolicyRenewalService service;

    public PolicyRenewalController(PolicyRenewalService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Policy> renew(@RequestParam UUID tenantId,
                                        @PathVariable UUID policyId,
                                        @Valid @RequestBody RenewPolicyRequest request) {
        Policy renewed = service.renew(tenantId, policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Integer.toString(renewed.version()))
                .body(renewed);
    }
}
