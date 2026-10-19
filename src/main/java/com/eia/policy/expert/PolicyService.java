package com.eia.policy.expert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PolicyService {
    private static final Logger LOG = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository repository;
    private final PolicyVersionService versionService;

    public PolicyService(PolicyRepository repository, PolicyVersionService versionService) {
        this.repository = repository;
        this.versionService = versionService;
    }

    /** Compatibility read: all policies. New callers should use the page form. */
    public List<Policy> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public PolicyPage list(UUID tenantId, int page, int size) {
        Pagination.validate(page, size);
        PolicyPageData data = repository.findPage(tenantId, page, size);
        return new PolicyPage(
                data.items(),
                page,
                size,
                data.total(),
                PageLinks.build("/internal/v1/policies",
                        Map.of("tenantId", tenantId.toString()), page, size, data.total())
        );
    }

    public Policy get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Policy not found: " + id,
                List.of(new ApiException.FieldIssue(
                        "policyId", "POLICY_NOT_FOUND", "Policy not found: " + id))));
    }

    /** Compatibility overload: creates without an idempotency scope. */
    @Transactional
    public Policy create(CreatePolicyRequest request) {
        return create(request, null).policy();
    }

    /**
     * Creates a policy and stores the idempotency key in the same transaction.
     * The key row is reserved before the policy row so a concurrent replay never
     * leaves a second policy behind. Same key and same request returns the
     * original policy with {@code replayed=true}; same key and a different
     * request is {@code 409 IDEMPOTENCY_CONFLICT}.
     */
    @Transactional
    public CreateResult create(CreatePolicyRequest request, String rawIdempotencyKey) {
        PolicyRules.validateCreate(request.status(), request.effectiveDate(), request.expirationDate(),
                request.premiumAmount(), request.limitAmount());

        String idempotencyKey = IdempotencyKeys.normalize(rawIdempotencyKey);
        if (idempotencyKey == null) {
            return new CreateResult(createPolicyWithInitialVersion(request, UUID.randomUUID()), false);
        }

        String requestHash = IdempotencyKeys.requestHash(request);
        Optional<IdempotencyRecord> existing =
                repository.findIdempotency(request.tenantId(), idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, request.tenantId());
        }

        UUID policyId = UUID.randomUUID();
        if (!repository.insertIdempotencyIfAbsent(
                request.tenantId(), idempotencyKey, requestHash, policyId)) {
            IdempotencyRecord raced = repository.findIdempotency(request.tenantId(), idempotencyKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_ERROR", "Idempotency reservation could not be resolved."));
            return replayOrConflict(raced, requestHash, request.tenantId());
        }

        return new CreateResult(createPolicyWithInitialVersion(request, policyId), false);
    }

    /** Compatibility overload: optimistic concurrency from the body only. */
    public Policy updateStatus(UUID tenantId, UUID id, UpdatePolicyStatusRequest request) {
        return updateStatus(tenantId, id, request, null);
    }

    /**
     * Applies one lifecycle transition. {@code If-Match} takes precedence over
     * the compatibility {@code expectedVersion} body field. A stale version is a
     * 409 problem carrying {@code currentVersion} and {@code expectedVersion}.
     * A transition to ISSUED requires a named insured and issues the pending
     * DRAFT policy version with its canonical snapshot hash.
     */
    @Transactional
    public Policy updateStatus(UUID tenantId, UUID id, UpdatePolicyStatusRequest request,
                               String ifMatch) {
        Integer headerVersion = EntityTags.parse(ifMatch);
        Policy current = get(tenantId, id);

        if (headerVersion != null && headerVersion != EntityTags.ANY
                && headerVersion != current.version()) {
            throw conflict(current.version(), headerVersion, "If-Match");
        }

        String targetStatus = PolicyRules.validateTransition(current.status(), request.status());
        if ("ISSUED".equals(targetStatus)) {
            PartyRules.requireNamedInsuredBeforeIssue(targetStatus,
                    repository.countNamedInsured(tenantId, id));
        }

        if (headerVersion == null && request.expectedVersion() != null
                && request.expectedVersion() != current.version()) {
            throw conflict(current.version(), request.expectedVersion(), "expectedVersion");
        }

        int expectedVersion;
        if (headerVersion == null) {
            expectedVersion = request.expectedVersion() == null
                    ? current.version()
                    : request.expectedVersion();
        } else {
            expectedVersion = headerVersion == EntityTags.ANY ? current.version() : headerVersion;
        }

        Policy updated = repository.updateStatus(tenantId, id, targetStatus, request.reason(),
                expectedVersion);
        if ("ISSUED".equals(targetStatus)) {
            versionService.issuePolicyVersion(updated, null);
        }
        return updated;
    }

    private Policy createPolicyWithInitialVersion(CreatePolicyRequest request, UUID policyId) {
        Policy policy = createPolicy(request, policyId);
        versionService.createInitialVersion(policy, UUID.randomUUID());
        return policy;
    }

    private Policy createPolicy(CreatePolicyRequest request, UUID policyId) {
        try {
            return repository.create(request, policyId);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_POLICY_NUMBER",
                    "Policy number already exists for this tenant: " + request.policyNumber());
        }
    }

    private CreateResult replayOrConflict(IdempotencyRecord record, String requestHash, UUID tenantId) {
        if (!record.requestHash().equals(requestHash)) {
            String message = "Idempotency-Key was already used with a different request.";
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message,
                    List.of(new ApiException.FieldIssue(
                            "Idempotency-Key", "IDEMPOTENCY_CONFLICT", message)),
                    Map.of("existingPolicyId", record.policyId().toString()));
        }
        Policy policy = repository.findById(tenantId, record.policyId())
                .orElseThrow(() -> {
                    LOG.error("Idempotency record references missing policy {} for tenant {}",
                            record.policyId(), tenantId);
                    return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                            "The stored idempotency record is inconsistent.");
                });
        return new CreateResult(policy, true);
    }

    private static ApiException conflict(int currentVersion, int expectedVersion, String field) {
        String message = "Expected version " + expectedVersion + " but policy is at version "
                + currentVersion + "; reload and retry";
        return new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message,
                List.of(new ApiException.FieldIssue(field, "STALE_VERSION", message)),
                Map.of("currentVersion", currentVersion, "expectedVersion", expectedVersion));
    }
}
