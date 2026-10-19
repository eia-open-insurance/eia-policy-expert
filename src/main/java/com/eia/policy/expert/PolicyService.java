package com.eia.policy.expert;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PolicyService {
    private final PolicyRepository repository;

    public PolicyService(PolicyRepository repository) {
        this.repository = repository;
    }

    public List<Policy> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Policy get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Policy not found: " + id));
    }

    public Policy create(CreatePolicyRequest request) {
        PolicyRules.validateCreate(request.status(), request.effectiveDate(), request.expirationDate(),
                request.premiumAmount(), request.limitAmount());
        try {
            return repository.create(request);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_POLICY_NUMBER",
                    "Policy number already exists for this tenant: " + request.policyNumber());
        }
    }

    public Policy updateStatus(UUID tenantId, UUID id, UpdatePolicyStatusRequest request) {
        Policy current = get(tenantId, id);
        String targetStatus = PolicyRules.validateTransition(current.status(), request.status());
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "Expected version " + request.expectedVersion() + " but policy is at version " + current.version());
        }
        return repository.updateStatus(tenantId, id, targetStatus, request.reason(), current.version());
    }
}
