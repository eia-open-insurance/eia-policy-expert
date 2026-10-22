package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Premium audit command. The external path is {@code /api/v1}; the domain
 * adapter keeps the {@code /internal/v1} alias, matching the other policy
 * command controllers.
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/premium-audit",
        "/internal/v1/policies/{policyId}/premium-audit"
})
public class PremiumAuditController {
    private static final String IF_MATCH = "If-Match";

    private final PremiumAuditService service;

    public PremiumAuditController(PremiumAuditService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Policy> audit(@RequestParam UUID tenantId,
                                        @PathVariable UUID policyId,
                                        @RequestHeader(value = IF_MATCH, required = false)
                                        String ifMatch,
                                        @Valid @RequestBody PremiumAuditRequest request) {
        Policy policy = service.audit(tenantId, policyId, request, ifMatch);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Integer.toString(policy.version()))
                .body(policy);
    }
}
