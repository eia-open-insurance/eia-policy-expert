package com.eia.policy.expert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Policy version history. The current version is derived (the single ISSUED
 * row, or the latest draft before issue) and its canonical hash is recomputed on
 * read, so SPEC POL-06 is enforced at the boundary rather than trusted.
 */
@Service
public class PolicyVersionService {
    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicySnapshotLoader snapshotLoader;

    public PolicyVersionService(PolicyRepository policyRepository,
                                PolicyVersionRepository versionRepository,
                                PolicySnapshotLoader snapshotLoader) {
        this.policyRepository = policyRepository;
        this.versionRepository = versionRepository;
        this.snapshotLoader = snapshotLoader;
    }

    public ItemPage<PolicyVersion> list(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        List<PolicyVersion> versions = versionRepository.findAll(tenantId, policyId);
        return SubPages.page(versions, page, size,
                "/internal/v1/policies/" + policyId + "/versions", tenantId);
    }

    public CurrentVersion current(UUID tenantId, UUID policyId) {
        Policy policy = requirePolicy(tenantId, policyId);
        PolicyVersion version = versionRepository.findIssued(tenantId, policyId)
                .or(() -> versionRepository.findLatest(tenantId, policyId))
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_VERSION_NOT_FOUND", "versionId",
                        "Policy " + policyId + " has no versions."));
        boolean issued = "ISSUED".equals(version.status());
        if (!issued) {
            return new CurrentVersion(version, true, false);
        }
        String recomputed = PolicyVersionHasher.hash(policy, version,
                snapshotLoader.load(tenantId, policyId));
        if (!recomputed.equals(version.immutableHash())) {
            throw PolicyErrors.conflict("VERSION_HASH_MISMATCH", "versionId",
                    "The current policy version hash does not match its snapshot (SPEC POL-06).",
                    java.util.Map.of("storedHash", version.immutableHash(),
                            "recomputedHash", recomputed));
        }
        return new CurrentVersion(version, true, true);
    }

    public PolicyVersion get(UUID tenantId, UUID policyId, UUID versionId) {
        requirePolicy(tenantId, policyId);
        return versionRepository.findById(tenantId, policyId, versionId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_VERSION_NOT_FOUND", "versionId",
                        "Policy version not found: " + versionId));
    }

    /** Creates the initial DRAFT version (change type BIND) with a policy. */
    @Transactional
    public PolicyVersion createInitialVersion(Policy policy, UUID versionId) {
        Instant now = Instant.now();
        PolicyVersion draft = new PolicyVersion(
                versionId, policy.tenantId(), policy.id(), 1,
                policy.policyNumber() + "-01", "BIND", "DRAFT", policy.effectiveDate(), null,
                null, null, null, null, null, null, "Policy created",
                null, "", now);
        PolicyVersion hashed = draft.withHash(
                PolicyVersionHasher.hash(policy, draft, PolicySnapshot.empty()));
        versionRepository.insert(hashed);
        return hashed;
    }

    /**
     * Issues the pending DRAFT version (or creates one for a legacy policy that
     * predates versioning), recomputing the hash from the live snapshot.
     */
    @Transactional
    public PolicyVersion issuePolicyVersion(Policy policy, UUID issuedBy) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        PolicyVersion draft = versionRepository.findDraft(tenantId, policyId).orElse(null);
        if (draft == null) {
            int versionNumber = versionRepository.nextVersionNumber(tenantId, policyId);
            draft = new PolicyVersion(UUID.randomUUID(), tenantId, policyId, versionNumber,
                    policy.policyNumber() + "-" + String.format("%02d", versionNumber),
                    "ISSUE", "DRAFT", policy.effectiveDate(), null, null, null, null, null,
                    null, null, "Policy issued", null, "", Instant.now());
            versionRepository.insert(draft);
        }
        Instant now = Instant.now();
        PolicyVersion candidate = draft.issued(now, issuedBy, null, "");
        String hash = PolicyVersionHasher.hash(policy, candidate,
                snapshotLoader.load(tenantId, policyId));
        int rows = versionRepository.issueDraft(tenantId, policyId, draft.id(), now, issuedBy,
                null, hash);
        if (rows == 0) {
            throw PolicyErrors.conflict("VERSION_ALREADY_ISSUED", "versionId",
                    "The policy version was already issued by another request; reload and retry.");
        }
        return candidate.withHash(hash);
    }

    public boolean isIssued(UUID tenantId, UUID policyId) {
        return versionRepository.findIssued(tenantId, policyId).isPresent();
    }

    /** The single ISSUED version, or the latest draft before first issue. */
    public PolicyVersion currentOrNull(UUID tenantId, UUID policyId) {
        return versionRepository.findIssued(tenantId, policyId)
                .or(() -> versionRepository.findLatest(tenantId, policyId))
                .orElse(null);
    }

    public LocalDate currentVersionEffectiveFrom(UUID tenantId, UUID policyId) {
        return versionRepository.findIssued(tenantId, policyId)
                .or(() -> versionRepository.findLatest(tenantId, policyId))
                .map(PolicyVersion::effectiveFrom)
                .orElse(null);
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }
}
