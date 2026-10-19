package com.eia.policy.expert;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy version history. The current version is a derived resource: the single
 * ISSUED version with its snapshot hash re-verified on read.
 */
@RestController
@RequestMapping("/internal/v1/policies/{policyId}")
public class PolicyVersionController {
    private final PolicyVersionService service;

    public PolicyVersionController(PolicyVersionService service) {
        this.service = service;
    }

    @GetMapping("/versions")
    public ItemPage<PolicyVersion> list(@RequestParam UUID tenantId,
                                        @PathVariable UUID policyId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.list(tenantId, policyId, page, size);
    }

    @GetMapping("/versions/current")
    public CurrentVersion current(@RequestParam UUID tenantId, @PathVariable UUID policyId) {
        return service.current(tenantId, policyId);
    }

    @GetMapping("/versions/{versionId}")
    public PolicyVersion get(@RequestParam UUID tenantId, @PathVariable UUID policyId,
                             @PathVariable UUID versionId) {
        return service.get(tenantId, policyId, versionId);
    }
}
