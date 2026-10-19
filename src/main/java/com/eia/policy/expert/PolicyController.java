package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/policies")
public class PolicyController {
    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<Policy> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public Policy get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Policy create(@Valid @RequestBody CreatePolicyRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}/status")
    public Policy updateStatus(@RequestParam UUID tenantId,
                               @PathVariable UUID id,
                               @Valid @RequestBody UpdatePolicyStatusRequest request) {
        return service.updateStatus(tenantId, id, request);
    }
}
